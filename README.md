# sNetVeri

[English](README-EN.md) | 简体中文

本仓库是 IWQoS 2026 论文 **Scalable Simulation-based Configuration Verification of DCNs via Destination-Independent Compression** 的代码实现。sNetVeri 面向大规模 BGP 数据中心网络配置验证，通过一次性、目的无关的网络压缩，在压缩后的逻辑拓扑上完成控制平面仿真，并基于仿真得到的转发表执行数据平面可达性验证。

## 代码组成

```text
.
├── compression/
│   ├── benchmarks/
│   │   ├── Acorn/          # TopologyZoo/BGPStream WAN 输入转换
│   │   └── Fattree/        # Fat-tree DCN 输入生成
│   ├── sNetVeri/           # sNetVeri 压缩与未压缩格式转换
│   ├── bonsai/             # Bonsai 风格逐目的压缩对比
│   ├── batfish/            # IOS-XR 配置生成与 Batfish 调用
│   └── requirements.txt    # Python 第三方依赖
├── simulation/             # Java/Maven BGP 控制平面仿真器
├── verification/           # Rust/Cargo 数据平面验证器
├── README.md
└── README-EN.md
```

端到端流程：

1. `compression/benchmarks` 生成 `*_network.json` 和 `*_policy.json`。
2. `compression/sNetVeri/compression.py` 生成压缩后的 `topo.json`、`policy.json` 和 `topo_info.txt`。
3. `simulation/` 读取 `topo.json`、`policy.json`，执行 BGP 仿真并输出 `routes/` 与 `results/`。
4. `verification/` 读取 `routes/` 与 `results/`，验证边缘设备间可达性。

## 环境依赖

- Python 3.10+
- Java 8+
- Maven 3.8+
- Rust 1.70+ 和 Cargo
- 可选：Batfish 服务，仅运行 Batfish 对比流程时需要

Python 依赖安装：

```bash
cd compression
pip install -r requirements.txt
```

当前 `requirements.txt` 只固定了 Batfish 对比脚本需要的 `pybatfish`。sNetVeri 压缩、Fat-tree 生成和 Acorn 输入转换主要使用 Python 标准库。

## 1. 输入生成与压缩

### 1.1 Fat-tree DCN

```bash
cd compression/benchmarks/Fattree
python3 construction.py --start 40 --end 40 --dest multiple --type HNM
```

参数：

- `--start`、`--end`：Fat-tree 端口数 `k` 的范围，脚本按 10 递增。
- `--dest`：`single` 表示只选择一个 ToR 宣告目的前缀；`multiple` 表示每个 ToR 都作为目的。
- `--type`：`M`、`NM`、`HNM`，对应不同的策略和非单调配置类型。

输出位于 `compression/benchmarks/Fattree/output/`。例如当前仓库中已有 `fattree-40-HNM_multi_network.json`、`fattree-40-HNM_multi_parse.txt` 和 `fattree_policy.json`。

### 1.2 WAN 输入

```bash
cd compression/benchmarks/Acorn
python3 construction.py --topo topologyzoo --dest multiple
python3 construction.py --topo bgpstream --dest single
```

参数：

- `--topo`：`topologyzoo` 或 `bgpstream`。
- `--dest`：`single` 使用输入中的单个目的节点；`multiple` 使用所有节点作为目的。

输出分别位于 `compression/benchmarks/Acorn/TopologyZoo/output/` 和 `compression/benchmarks/Acorn/BGPStream/output/`。

### 1.3 sNetVeri 压缩

```bash
cd compression/sNetVeri
python3 compression.py
```

该脚本按固定目录批处理：

- `../benchmarks/Acorn/BGPStream/output/`
- `../benchmarks/Acorn/TopologyZoo/output/`
- `../benchmarks/Fattree/output/`

输出目录格式与仿真器输入格式一致：

```text
compression/sNetVeri/compression/<数据集>/<拓扑名>/
├── topo.json       # 压缩后的逻辑拓扑
├── policy.json     # 策略文件
└── topo_info.txt   # 压缩统计
```

压缩器会根据角色、未处理邻居集合、BGP 偏好参数、策略、聚合路由、区域和设备组生成节点指纹，再结合连通性、层次剥离和 AS 冲突约束决定合并或拆分。

### 1.4 未压缩格式转换

```bash
cd compression/sNetVeri
python3 wo_compression.py --input ../benchmarks/Fattree/output --type benchmarks
```

该脚本不做节点合并，只把 `*_network.json` 转成仿真器可读格式。输出命名已与 `compression.py` 保持一致：

```text
compression/sNetVeri/wo_compression/<type>/<数据集>/<拓扑名>/
├── topo.json
├── policy.json
└── topo_info.txt
```

`--type` 可选 `benchmarks` 或 `bonsai`。

## 2. 对比工具

Bonsai 风格压缩：

```bash
cd compression/bonsai
python3 compression.py --input ../benchmarks/Fattree/output --count 100
```

生成 Cisco IOS-XR 配置：

```bash
cd compression/batfish
python3 construction.py --input ../benchmarks/Fattree/output --type benchmarks
```

运行 Batfish 仿真或可达性验证：

```bash
cd compression/batfish
python3 batfish.py --input benchmarks/Fattree/fattree-40-HNM_network --type verification
```

Batfish 脚本默认连接 `localhost` 上的 Batfish 服务。`--input` 应指向包含 `configs/` 和 `config_result.json` 的 snapshot 目录。

## 3. 控制平面仿真

`simulation/` 是 Maven 项目，入口为 `xmu.Main`。当前版本只保留 Dijkstra 风格 BGP 仿真流程，命令行参数由 `CLIParser` 定义。

输入目录必须包含：

```text
<configPath>/
├── topo.json
└── policy.json
```

构建：

```bash
cd simulation
mvn clean package
```

运行：

```bash
java -jar target/sNetVeri-1.0-SNAPSHOT.jar \
  --configPath ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi \
  --batchSize 100 \
  --print 1 
```

参数：

- `--configPath`：包含 `topo.json` 和 `policy.json` 的目录。
- `--batchSize`：按前缀分批计算的批大小。
- `--print`：`0` 不输出验证输入；`1` 输出 `routes/` 和 `results/`。

当 `--print 1` 时，仿真器会在 `<configPath>/` 下生成：

```text
<configPath>/
├── routes/              # 每个逻辑节点的转发表
└── results/
    ├── edge_devices
    ├── devices.json
    ├── topology.json
    └── packet_space.json
```

## 4. 数据平面验证

Rust 验证器位于 `verification/`，包名为 `topoNet`。它读取仿真器输出目录：

```text
<filedir>/
├── routes/
└── results/
    ├── edge_devices
    ├── devices.json
    ├── topology.json
    └── packet_space.json
```

构建并运行：

```bash
cd verification
cargo run --release -- --filedir ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi
```

也可以直接运行二进制：

```bash
cd verification
cargo build --release
./target/release/topoNet --filedir ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi
```

验证器会输出前缀编码、规则编码、packet space 编码、build 阶段、verification 阶段和总耗时，并统计 reachable、unreachable 与总源宿设备对数量。

## 输入文件说明

- `*_network.json`：压缩前的网络配置，包含设备 IP、角色、AS 号、VRF、接口和 BGP 邻居。
- `*_policy.json` / `policy.json`：策略列表，包含匹配条件和属性修改动作。
- `topo.json`：仿真器输入的逻辑拓扑；压缩版本中，一个逻辑节点可包含多个物理设备。
- `routes/<Node>`：仿真器输出的逻辑节点转发表。
- `results/topology.json`：逻辑节点端口级拓扑。
- `results/edge_devices`：边缘逻辑节点列表。
- `results/devices.json`：逻辑节点到物理设备 IP 的映射。
- `results/packet_space.json`：目的前缀及其所属物理设备。

## 复现建议

- 建议先从 `fattree-40-HNM_multi` 或更小规模开始，确认压缩、仿真、验证三阶段连通后再扩大规模。
- 多数 Python 脚本使用相对路径，建议从脚本所在目录运行。
- `verification/target/` 和 `simulation/target/` 是构建产物，不需要手动编辑。
- 运行 Batfish 对比前，需要先启动 Batfish 服务。

## 引用

如果使用本仓库，请引用论文：

```bibtex
@inproceedings{snetveri2026,
  title     = {Scalable Simulation-based Configuration Verification of DCNs via Destination-Independent Compression},
  author    = {Zhang, Mengrui and Zheng, Xiaoqiang and Zhu, Letian and Fang, Xing and You, Lizhao and Liu, Zhang and Yao, Ziyang and Wang, Yang and Wen, Rui and Zhang, Zhi and Sun, Ronghua and Zhong, Yuanhui and Li, Haihua and Yuan, Fei and Xiang, Qiao},
  booktitle = {IEEE/ACM International Symposium on Quality of Service (IWQoS)},
  year      = {2026}
}
```
