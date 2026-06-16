# sNetVeri

English | [简体中文](README.md)

This repository contains the implementation for the IWQoS 2026 paper **Scalable Simulation-based Configuration Verification of DCNs via Destination-Independent Compression**. sNetVeri targets configuration verification for large BGP data center networks. It performs destination-independent one-shot compression, simulates the control plane on the compressed logical topology, and verifies data-plane reachability from the simulated forwarding state.

## Code Layout

```text
.
├── compression/
│   ├── benchmarks/
│   │   ├── Acorn/          # TopologyZoo/BGPStream WAN input conversion
│   │   └── Fattree/        # Fat-tree DCN input generation
│   ├── sNetVeri/           # sNetVeri compression and uncompressed conversion
│   ├── bonsai/             # Bonsai-style per-destination compression baseline
│   ├── batfish/            # IOS-XR generation and Batfish runner
│   └── requirements.txt    # Python third-party dependencies
├── simulation/             # Java/Maven BGP control-plane simulator
├── verification/           # Rust/Cargo data-plane verifier
├── README.md
└── README-EN.md
```

End-to-end workflow:

1. `compression/benchmarks` generates `*_network.json` and `*_policy.json`.
2. `compression/sNetVeri/compression.py` generates compressed `topo.json`, `policy.json`, and `topo_info.txt`.
3. `simulation/` reads `topo.json` and `policy.json`, runs BGP simulation, and writes `routes/` and `results/`.
4. `verification/` reads `routes/` and `results/` and verifies edge-device reachability.

## Requirements

- Python 3.10+
- Java 8+
- Maven 3.8+
- Rust 1.70+ and Cargo
- Optional: a Batfish service, only for the Batfish comparison workflow

Install Python dependencies:

```bash
cd compression
pip install -r requirements.txt
```

The current `requirements.txt` pins `pybatfish`, which is only needed by the Batfish comparison scripts. The sNetVeri compressor, Fat-tree generator, and Acorn input converter mostly use the Python standard library.

## 1. Input Generation and Compression

### 1.1 Fat-tree DCNs

```bash
cd compression/benchmarks/Fattree
python3 construction.py --start 40 --end 40 --dest multiple --type HNM
```

Arguments:

- `--start`, `--end`: Fat-tree port count `k`; the script increases `k` by 10.
- `--dest`: `single` announces one ToR destination; `multiple` makes every ToR a destination.
- `--type`: `M`, `NM`, or `HNM`, representing different policy and non-monotonic configuration types.

Outputs are written to `compression/benchmarks/Fattree/output/`. For example, the current repository contains `fattree-40-HNM_multi_network.json`, `fattree-40-HNM_multi_parse.txt`, and `fattree_policy.json`.

### 1.2 WAN Inputs

```bash
cd compression/benchmarks/Acorn
python3 construction.py --topo topologyzoo --dest multiple
python3 construction.py --topo bgpstream --dest single
```

Arguments:

- `--topo`: `topologyzoo` or `bgpstream`.
- `--dest`: `single` uses the destination in the input; `multiple` uses all nodes as destinations.

Outputs are written to `compression/benchmarks/Acorn/TopologyZoo/output/` and `compression/benchmarks/Acorn/BGPStream/output/`.

### 1.3 sNetVeri Compression

```bash
cd compression/sNetVeri
python3 compression.py
```

The script batch-processes these fixed input directories:

- `../benchmarks/Acorn/BGPStream/output/`
- `../benchmarks/Acorn/TopologyZoo/output/`
- `../benchmarks/Fattree/output/`

Output directories follow the simulator input layout:

```text
compression/sNetVeri/compression/<dataset>/<topology>/
├── topo.json       # compressed logical topology
├── policy.json     # policy file
└── topo_info.txt   # compression statistics
```

The compressor fingerprints nodes by role, remaining neighbors, BGP preference parameters, policies, aggregate routes, region, and device group. It then uses connectivity, layer peeling, and AS-conflict constraints to decide whether nodes should be merged or split.

### 1.4 Uncompressed Conversion

```bash
cd compression/sNetVeri
python3 wo_compression.py --input ../benchmarks/Fattree/output --type benchmarks
```

This script does not merge nodes. It only converts `*_network.json` files into the simulator format. Its output naming is aligned with `compression.py`:

```text
compression/sNetVeri/wo_compression/<type>/<dataset>/<topology>/
├── topo.json
├── policy.json
└── topo_info.txt
```

`--type` can be `benchmarks` or `bonsai`.

## 2. Baseline Tools

Bonsai-style compression:

```bash
cd compression/bonsai
python3 compression.py --input ../benchmarks/Fattree/output --count 100
```

Generate Cisco IOS-XR configurations:

```bash
cd compression/batfish
python3 construction.py --input ../benchmarks/Fattree/output --type benchmarks
```

Run Batfish simulation or reachability verification:

```bash
cd compression/batfish
python3 batfish.py --input benchmarks/Fattree/fattree-40-HNM_network --type verification
```

The Batfish runner connects to a Batfish service on `localhost`. `--input` should point to a snapshot directory containing `configs/` and `config_result.json`.

## 3. Control-Plane Simulation

`simulation/` is a Maven project whose entry point is `xmu.Main`. The current version keeps the Dijkstra-style BGP simulation workflow. Command-line options are defined by `CLIParser`.

The input directory must contain:

```text
<configPath>/
├── topo.json
└── policy.json
```

Build:

```bash
cd simulation
mvn clean package
```

Run:

```bash
java -jar target/sNetVeri-1.0-SNAPSHOT.jar \
  --configPath ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi \
  --batchSize 100 \
  --print 1 
```

Arguments:

- `--configPath`: directory containing `topo.json` and `policy.json`.
- `--batchSize`: number of prefixes processed per batch.
- `--print`: `0` disables verifier-input output; `1` writes `routes/` and `results/`.

When `--print 1` is used, the simulator writes:

```text
<configPath>/
├── routes/              # forwarding tables for logical nodes
└── results/
    ├── edge_devices
    ├── devices.json
    ├── topology.json
    └── packet_space.json
```

## 4. Data-Plane Verification

The Rust verifier is in `verification/`; its Cargo package name is `topoNet`. It reads the simulator output directory:

```text
<filedir>/
├── routes/
└── results/
    ├── edge_devices
    ├── devices.json
    ├── topology.json
    └── packet_space.json
```

Build and run:

```bash
cd verification
cargo run --release -- --filedir ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi
```

Or run the binary directly:

```bash
cd verification
cargo build --release
./target/release/topoNet --filedir ../compression/sNetVeri/compression/Fattree/fattree-40-HNM_multi
```

The verifier prints prefix encoding time, rule encoding time, packet-space encoding time, build time, verification time, total time, and the reachable, unreachable, and total source-destination device-pair counts.

## Input Files

- `*_network.json`: pre-compression network configuration, including device IPs, roles, AS numbers, VRFs, interfaces, and BGP neighbors.
- `*_policy.json` / `policy.json`: route-policy list, including match conditions and route-attribute actions.
- `topo.json`: logical topology consumed by the simulator; in compressed runs, one logical node may contain multiple physical devices.
- `routes/<Node>`: simulator-generated forwarding table for a logical node.
- `results/topology.json`: port-level logical topology.
- `results/edge_devices`: edge logical node list.
- `results/devices.json`: logical-node to physical-device IP mapping.
- `results/packet_space.json`: destination prefixes and their physical devices.

## Reproduction Notes

- Start with `fattree-40-HNM_multi` or a smaller local test before scaling up.
- Many Python scripts use relative paths, so run them from their own directories.
- `verification/target/` and `simulation/target/` are build outputs and do not need manual editing.
- Start the Batfish service before running Batfish baselines.

## Citation

If you use this repository, please cite:

```bibtex
@inproceedings{snetveri2026,
  title     = {Scalable Simulation-based Configuration Verification of DCNs via Destination-Independent Compression},
  author    = {Zhang, Mengrui and Zheng, Xiaoqiang and Zhu, Letian and Fang, Xing and You, Lizhao and Liu, Zhang and Yao, Ziyang and Wang, Yang and Wen, Rui and Zhang, Zhi and Sun, Ronghua and Zhong, Yuanhui and Li, Haihua and Yuan, Fei and Xiang, Qiao},
  booktitle = {IEEE/ACM International Symposium on Quality of Service (IWQoS)},
  year      = {2026}
}
```
