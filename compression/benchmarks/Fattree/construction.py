import argparse
import json
import os
import ipaddress
import time
from collections import defaultdict


def generate_as(nodes, k, start=65000):
    """
    为 Fat-Tree 节点分配 AS 号
    规则：
    1. Tor 和 Aggregation 每个节点拥有独立的 AS。
    2. Core 节点如果连接的 Aggregation 集合相同，则 AS 相同。
    """
    as_map = {}
    current_as = start

    num_in_pod = k // 2

    # 1. 为 Tor 和 Aggregation 分配独立 AS
    for node in nodes:
        if node.startswith('T') or node.startswith('A'):
            as_map[node] = current_as
            current_as += 1

    # 2. 为 Core 分配 AS (分组分配)
    # Fat-Tree 规则：C1..C(k/2) 连往每个 Pod 的第1个 Agg
    # 因此 C1..C(k/2) 是一组，C(k/2+1)..Ck 是一组...
    core_nodes = [n for n in nodes if n.startswith('C')]
    # 按照编号数字排序，确保 C1, C2, C3... 的顺序
    core_nodes.sort(key=lambda x: int(x[1:]))

    for i in range(len(core_nodes)):
        # 每 num_in_pod (即 k/2) 个 Core 节点换一个 AS
        if i > 0 and i % num_in_pod == 0:
            current_as += 1

        as_map[core_nodes[i]] = current_as

    # 为最后一组 Core 后的下一个节点预留 AS 增加
    return as_map
def generate_interface(device_ips,adjacency_list,start_ip="60.0.0.0"):
    peers = {}
    interfaces = defaultdict(list)
    current_base_ip = ipaddress.IPv4Address(start_ip)
    node_interface_counters = defaultdict(lambda: 2)
    # 记录已经处理过的链路，避免 A-B 和 B-A 重复分配不同的 IP
    processed_links = set()

    for node in adjacency_list:
       interfaces[node].append({
                "ip": device_ips[node],
                "interfaceId": 1,
                "interfaceName": "Loopback0"})
       for neighbor in adjacency_list[node]:
        try:
            # 使用 eval 或简易字符串处理提取node
            peer_pair = (node, neighbor)
            u, v = peer_pair
        except:
            continue

        # 创建一个无序的键来代表这条链路
        link_id = tuple(sorted([u, v]))

        if link_id not in processed_links:
            # 为这对邻居分配一个新的 /31 网段
            # .0 给一端，.1 给另一端
            ip_a = str(current_base_ip)
            ip_b = str(current_base_ip + 1)
            id_u = node_interface_counters[u]
            id_v = node_interface_counters[v]

            node_interface_counters[u] += 1
            node_interface_counters[v] += 1
            # 存储映射关系（双向都存，方便后续查询）
            peers[(u, v)] = {
                "local_ip": ip_a,
                "peer_ip": ip_b,
                "mask": "31",
                "local_interface_id": id_u,
                "local_interface_name": f"Gi0/{id_u}",
                "remote_interface_id": id_v,
                "remote_interface_name": f"Gi0/{id_v}"
            }
            peers[(v, u)] = {
                "local_ip": ip_b,
                "peer_ip": ip_a,
                "mask": "31",
                "local_interface_id": id_v,
                "local_interface_name": f"Gi0/{id_v}",
                "remote_interface_id": id_u,
                "remote_interface_name": f"Gi0/{id_u}"
            }

            interfaces[u].append({
                "ip": ip_a,
                "interfaceId": id_u,
                "interfaceName": f"Gi0/{id_u}",
            })
            interfaces[v].append({
                "ip": ip_b,
                "interfaceId": id_v,
                "interfaceName": f"Gi0/{id_v}",
            })
            processed_links.add(link_id)
            # 移动到下一个 /31 网段 (增加 256 个 IP)
            current_base_ip += 256

    return interfaces,peers
def generate_device_ips(nodes, start_ip="20.0.0.1"):
    """
    为 .in 文件中的每个节点分配递增的 IP 地址
    """
    ip_map = {}
    current_ip = ipaddress.IPv4Address(start_ip)

    for node in nodes:
        ip_map[node] = str(current_ip)
        current_ip += 2  # 自动处理进位，例如 20.0.0.255 -> 20.0.1.0
    return ip_map
def generate_all(nodes,adjacency_list,dest,device_ips,interface,peers,policy_id,device_as,output_file):
    results = []


    for node in nodes:
        route_policy = []
        device_name=str(node)
        device_ip = device_ips[node]
        device_role =""
        if node.startswith('T'):
            device_role="TOR"
        elif node.startswith('A'):
            device_role="AGGREGATION"
        elif node.startswith('C'):
            device_role="CORE"
        device_group="group"
        region="region"
        logi_area="area"
        routerId= [device_ip]
        asNum=str(device_as[node])
        vrfMap=[]
        network_route_dto=[]
        if node in dest:
            if interface[node][1].get("interfaceName") == "Loopback0":
                interface_str = f"{interface[node][0].get('ip')}/{31}"
            else:
                interface_str = f"{interface[node][1].get('ip')}/{31}"
            # interface_str = f"{interface[node][1]['ip']}/{31}"
            network_route_dto.append({
                "segment": str(ipaddress.ip_interface(interface_str).network),
                "NetworkPolicyName": "null",
                "id": 0
            })
        network_route_list = {
            "NetworkRouteDto": network_route_dto,
        }
        import_route_list = {
            "ImportRouteDto": [],
        }
        aggregate_route_list = {
            "AggregateRouteDto": [],
        }
        vrfMap.append({
            "deviceIp":device_ip,
            "vrfName": "_public_",
            "maxLbNum": "64",
            "lbAsPathRelax": "true",
            "preferenceExternal": "120",
            "preferenceInternal": "255",
            "preferenceLocal": "200",
            "networkRouteList": network_route_list,
            "importRouteList": import_route_list,
            "aggregateRouteList": aggregate_route_list,
        }
        )
        interface_list=[]
        for interf in interface[node]:
            if interf["interfaceName"] == "Loopback0":
                interface_list.append({
                    "segment": interf["ip"]+"/32",
                    "nextHop": "null",
                    "deviceIp": device_ip,
                    "interfaceName": interf["interfaceName"],
                    "interfaceId": interf["interfaceId"],
                    "peerIp": interf["ip"],
                    "mask":"32",
                    "type": "PHYSICAL"
                })
            else:
                interface_list.append({
                    "segment": interf["ip"] + "/31",
                    "nextHop": "null",
                    "deviceIp": device_ip,
                    "interfaceName": interf["interfaceName"],
                    "interfaceId": interf["interfaceId"],
                    "peerIp": interf["ip"],
                    "mask": "31",
                    "type": "PHYSICAL"
                })
        edge_list=[]
        for neighbor in adjacency_list[node]:
            fwd_key = (node, neighbor)
            rev_key = (neighbor, node)
            p_fwd = policy_id.get(fwd_key, {"export": 0, "import": 0})
            p_rev = policy_id.get(rev_key, {"export": 0, "import": 0})
            local_ex = p_fwd["export"]
            remote_im = p_fwd["import"]
            remote_ex = p_rev["export"]
            local_im = p_rev["import"]
            if local_ex!=0:
                route_policy.append({
                    "id": local_ex,
                    "policyName": "policy_" + str(local_ex)
                })
            if local_im!=0:
                route_policy.append({
                    "id": local_im,
                    "policyName": "policy_" + str(local_im) if local_im != 0 else "null"
                })
            peer_list=[]
            local_peers=[]
            local_peers.append({
                "peerIp":peers[(node,neighbor)]["local_ip"],
                "interfaceName":peers[(node,neighbor)]["local_interface_name"],
            })
            remote_peers=[]
            remote_peers.append({
                "peerIp":peers[(node,neighbor)]["peer_ip"],
                "interfaceName":peers[(node,neighbor)]["remote_interface_name"],
            })
            peer_list.append({
                "localIp": device_ip,
                "localDeviceName": str(node),
                "remoteIp": device_ips[neighbor],
                "remoteDeviceName": str(neighbor),
                "localPeers":local_peers,
                "remotePeers": remote_peers,
                "remoteAsNumber": str(device_as[neighbor]),
                "localAsNumber": str(device_as[node]),
                    }
            )
            edge_list.append({
                "localIp": device_ip,
                "localDeviceName":str(node),
                "remoteIp": device_ips[neighbor],
                "remoteDeviceName":str(neighbor),
                "localExportPolicyName": "null" if local_ex == 0 else "policy_" + str(local_ex),
                "localExportPolicyId":local_ex,
                "localImportPolicyName": "null"if local_im == 0 else "policy_" + str(local_im),
                "localImportPolicyId": local_im,
                "remoteExportPolicyName": "null" if remote_ex == 0 else "policy_" + str(remote_ex),
                "remoteExportPolicyId":remote_ex,
                "remoteImportPolicyName": "null"if remote_im == 0 else "policy_" + str(remote_im),
                "remoteImportPolicyId": remote_im,
                "peerList":peer_list
            })

        results.append({
            "deviceName": device_name,
            "deviceIp": device_ip,
            "deviceRole": device_role,
            "deviceGroup": device_group,
            "region": region,
            "logiArea": logi_area,
            "routerId": routerId,
            "asNum": asNum,
            "vrfMap": vrfMap,
            "routePolicy": route_policy,
            "interfaceList": interface_list,
            "edgeList": edge_list
        })

    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=4, ensure_ascii=False)

def generate_fattree(k,type):
    """
    生成 Fat-Tree 拓扑的邻接关系
    k: 端口数 (必须为偶数)
    """
    if k % 2 != 0:
        raise ValueError("k 必须是偶数")
    nodes = []
    adj = {}
    dest=[]
    policy_id={}
    num_in_pod = k // 2
    num_pods = k
    num_core = (k // 2) ** 2

    # 辅助函数：初始化 policy_id 结构
    def set_policy(u, v, exp, imp):
        policy_id[(u, v)] = {"export": exp, "import": imp}
    # 1. 生成节点名称
    tor_nodes = [f"T{i+1}" for i in range(num_pods * num_in_pod)]
    agg_nodes = [f"A{i+1}" for i in range(num_pods * num_in_pod)]
    core_nodes = [f"C{i+1}" for i in range(num_core)]
    dest=tor_nodes
    nodes = tor_nodes + agg_nodes + core_nodes
    for node in nodes:
        adj[node] = []

    # 2. 连接 Pod 内部 (Tor <-> Aggregation)
    # 每个 Pod 内部是一个全连接（Bipartite）
    for pod in range(num_pods):
        pod_offset = pod * num_in_pod
        for t_idx in range(num_in_pod):
            tor_name = tor_nodes[pod_offset + t_idx]
            for a_idx in range(num_in_pod):
                agg_name = agg_nodes[pod_offset + a_idx]
                # 双向连接
                adj[tor_name].append(agg_name)
                adj[agg_name].append(tor_name)

                # 默认策略 (Type M)
                exp_t, imp_t = 0, 0
                exp_a, imp_a = 0, 0

                if type == "NM":
                    # Tor 连接其 Pod 内第一个 Agg (a_idx == 0)
                    if a_idx == 0:
                        exp_t, imp_t = 2, 0
                elif type == "HNM":
                    # Tor 连接其 Pod 内第一个 Agg
                    if a_idx == 0:
                        exp_t, imp_t = 2, 0
                    exp_a, imp_a = 0, 1

                set_policy(tor_name, agg_name, exp_t, imp_t)
                set_policy(agg_name, tor_name, exp_a, imp_a)
    # 3. 连接 Core 和 Aggregation
    # 每个 Aggregation 交换机连接到 k/2 个 Core 交换机
    for pod in range(num_pods):
        for a_idx in range(num_in_pod):
            agg_name = agg_nodes[pod * num_in_pod + a_idx]
            # a_idx 决定了该 Agg 交换机连接哪一组 Core 节点
            # 规则：第 i 个 Agg 交换机连接到第 i 组 Core 节点（共 k/2 个）
            for c_idx in range(num_in_pod):
                core_name = core_nodes[a_idx * num_in_pod + c_idx]
                adj[agg_name].append(core_name)
                adj[core_name].append(agg_name)
                # 默认策略 (Type M)
                exp_a, imp_a = 0, 0
                exp_c, imp_c = 0, 0

                if type in ["NM", "HNM"]:
                    # 每个 Agg-Core 连接
                    exp_a, imp_a = 3, 0
                    exp_c, imp_c = 0, 1
                set_policy(agg_name, core_name, exp_a, imp_a)
                set_policy(core_name, agg_name, exp_c, imp_c)
    return nodes, adj, dest,policy_id

def export_results(nodes=0,edges=0,execution_time=0.0,result_file="parse_result.txt"):
    with open(result_file, 'w') as f:
        f.write(f"Node Count: {nodes}\n")
        f.write(f"Edge Count: {edges}\n")
        f.write(f"Total Parse Time: {execution_time:.4f}S\n")
def batch_process_fattrees(output_dir, start=40, end=90, dest_type="single",types=["M", "NM", "HNM"]):
    """
    批量生成不同类型和规模的 Fat-Tree 拓扑配置
    """
    # 基础输出目录
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # 遍历每种模式: M, NM, HNM
    for t in types:
        # # 为每个 type 创建独立的子文件夹
        # type_dir = os.path.join(output_dir, t)
        # if not os.path.exists(type_dir):
        #     os.makedirs(type_dir)
        #     print(f"创建分类目录: {type_dir}")

        # 遍历 k 值: 40, 50, 60, 70, 80, 90
        # range(start, end + 1, 10) 表示从 start 开始，到 end 结束，步长为 10
        for k in range(start, end + 1, 10):

            base_name = f"fattree-{k}-{t}"
            print(f"正在处理模式 {t} 的规模 k={k} ...")
            start_time = time.time()
            # 1. 生成拓扑和策略 ID 映射
            nodes, adj, dest, policy_id = generate_fattree(k, t)
            dests=[]
            if dest_type == "single":
                dests = ["T1"]
            else:
                dests=dest
            # 3. 分配设备 Loopback IP
            device_ips = generate_device_ips(nodes)

            # 2. 分配接口 IP 和邻居对信息
            interfaces, peers = generate_interface(device_ips, adj)
            # 4. 生成 AS 号分配 (传入 k 以满足 Core 节点的 AS 分组逻辑)
            device_as = generate_as(nodes, k)

            # 5. 定义输出路径，放入对应的 type 文件夹中
            if dest_type == "single":
                network_output = os.path.join(output_dir, f"{base_name}_network.json")
                parse_output = os.path.join(output_dir, f"{base_name}_parse.txt")
            else:
                network_output = os.path.join(output_dir, f"{base_name}_multi_network.json")
                parse_output = os.path.join(output_dir, f"{base_name}_multi_parse.txt")
            # 6. 生成最终的全量网络配置 JSON
            try:
                generate_all(
                    nodes,
                    adj,
                    dests,
                    device_ips,
                    interfaces,
                    peers,
                    policy_id,
                    device_as,
                    network_output
                )
            except Exception as e:
                print(f"处理 {base_name} 时发生错误: {e}")
            end_time = time.time()
            execution_time = end_time - start_time
            edges=0
            for n in adj:
                edges += len(adj[n])
            export_results(nodes=len(nodes), edges=edges, execution_time=execution_time, result_file=parse_output)
    print("\n所有任务处理完成！")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Run Fattree Configuration Construction"
    )
    parser.add_argument(
        "--start",
        required=True,
        help="start k index",
    )
    parser.add_argument(
        "--end",
        required=True,
        help="end k index",
    )
    parser.add_argument(
        "--dest",
        required=True,
        default="single",
        choices=["single", "multiple"],
        help="Destination type"
    )
    parser.add_argument(
        "--type",
        required=True,
        default="M",
        choices=["M", "NM","HNM"],
        help="Destination type"
    )
    args = parser.parse_args()

    start = int(args.start)
    end = int(args.end)
    dest = args.dest
    type = args.type
    types=[]
    types.append(type)
    batch_process_fattrees("./output",start=start,end=end,dest_type=dest,types=types)