import argparse
import glob
import hashlib
import json
import os
import ipaddress
import time
from collections import defaultdict


def read_acorn_input(file_path):
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"找不到文件: {file_path}")

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data
    except json.JSONDecodeError as e:
        print(f"文件格式错误，无法解析 JSON: {e}")
        return None
    except Exception as e:
        print(f"读取文件时发生错误: {e}")
        return None
def generate_as(nodes,start=65000):
    as_map={}
    current_as=start
    for node in nodes:
        as_map[node] = current_as
        current_as += 1
    return as_map
def generate_policy(policies,device_as,output_file):
    unique_policies = []  # 存储去重后的策略对象
    policy_content_to_id = {}  # 建立 内容摘要 -> ID 的映射
    key_to_policy_id = {}  # 建立 (AS_A, AS_B) -> ID 的映射
    unique_policies.append({
        "name": "null",
        "id": 0,
        "policy": []
    })
    current_id = 1

    # 1. 遍历 ACORN 数据中的所有策略
    for edge_key, acorn_rules in policies.items():
        # 将 rules 列表转换为字符串（用于比较内容是否完全一致）
        # 使用 sort_keys 确保字典顺序一致
        policy_str = json.dumps(acorn_rules, sort_keys=True)
        # 使用 MD5 摘要作为内容的唯一标识
        content_hash = hashlib.md5(policy_str.encode('utf-8')).hexdigest()

        if content_hash not in policy_content_to_id:
            # 这是一个新的唯一策略内容，创建转换后的格式
            policy_id = current_id
            policy_content_to_id[content_hash] = policy_id

            # 格式化为你需要的 JSON 结构
            policy_obj = {
                "name": f"policy_{policy_id}",
                "id": policy_id,
                "policy": []
            }

            # 填充具体的 rule 节点
            for rule in acorn_rules:
                match_dto = []
                for m in rule.get("match", []):
                    if m.get("match_attr") == "comm":
                        match_dto.append({
                            "type": "ip_community_filter",
                            "policy": str(m.get("match_vals", [0])[0]),
                            "matchMode": rule.get("result", "permit")
                        })
                        if len(m.get("match_vals"))>1:
                            print("comm>1")
                            print(m.get("match_vals"))
                    else:
                        print(m.get("match_attr"))
                pathlen_action = next((a for a in rule.get("actions", []) if a.get("set_attr") == "pathlen"), None)
                source_as =device_as[eval(edge_key)[0]]
                if pathlen_action:
                    # 如果有 pathlen, 根据 set_val 复制 source_as
                    count = int(pathlen_action.get("set_val", 0))
                    as_path_value = " ".join([str(source_as)] * count)
                    if pathlen_action.get("set_type")=="incr":
                        as_path_op = "additive"
                    else:
                        print("pathlen action", pathlen_action)
                        as_path_op = "multiplicative"
                else:
                    # 如果没有 pathlen, 保持默认
                    as_path_value = "null"
                    as_path_op = "null"  # 或者根据你的需求保持为 "additive"
                node_entry = {
                    "node": str(rule.get("seq_num", "10")),
                    "rule": rule.get("result", "permit"),
                    "matchConditions":  match_dto if match_dto else [],
                    "ApplyActionDto": {
                        "communityValue": next(
                            (str(a.get("set_val")) for a in rule.get("actions", []) if a.get("set_attr") == "comm"),
                            "0"),
                        "asPath": as_path_value,
                        "asPathOperation": as_path_op,
                        "med": "null",
                        "locPrf": next(
                            (str(a.get("set_val")) for a in rule.get("actions", []) if a.get("set_attr") == "lp"),
                            "null"),
                        "preferredValue": "null"
                    }
                }
                policy_obj["policy"].append(node_entry)

            unique_policies.append(policy_obj)
            current_id += 1
        else:
            # 内容已存在，获取已有的 ID
            policy_id = policy_content_to_id[content_hash]

        # 记录该 key 对应的 ID
        key_to_policy_id[edge_key] = policy_id

    # 2. 将去重后的策略库保存到文件
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(unique_policies, f, indent=4, ensure_ascii=False)

    print(f"策略去重完成！总条数: {len(policies)}, 去重后唯一策略数: {len(unique_policies)}")

    # 返回 key 对应 ID 的映射，供构建 edgeList 使用
    return key_to_policy_id

def generate_device_ips(nodes, start_ip="20.0.0.1"):
    """
    为 .in 文件中的每个节点分配递增的 IP 地址
    """
    ip_map = {}
    current_ip = ipaddress.IPv4Address(start_ip)

    for node in nodes:
        ip_map[node] = str(current_ip)
        current_ip += 1  # 自动处理进位，例如 20.0.0.255 -> 20.0.1.0
    return ip_map
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
            # 使用 eval 或简易字符串处理提取 AS 号
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
            # 移动到下一个 /31 网段 (增加 2 个 IP)
            current_base_ip += 2

    return interfaces,peers

def normalize_device_name(node):
    device_name = str(node)

    # 如果只有数字 → 前面加 Node
    if device_name.isdigit():
        device_name = f"Node{device_name}"
    # 如果包含空格 → 替换为 _
    device_name = device_name.replace(" ", '').replace('_','').replace(',','').replace('-','').replace('?','')
    return device_name
def generate_all(nodes,adjacency_list,dest,device_ips,interface,peers,policy_id,device_as,output_file):
    results = []
    for node in nodes:
        route_policy=[]
        device_name=normalize_device_name(node)
        device_ip = device_ips[node]
        device_role="Router"
        device_group="group"
        region="region"
        logi_area="area"
        routerId= [device_ip]
        asNum=str(device_as[node])
        vrfMap=[]
        network_route_dto=[]
        if node == "Bucaresti":
            print(interface)
        if node in dest:
            if interface[node][1].get("interfaceName") == "Loopback0":
                interface_str = f"{interface[node][0].get('ip')}/{31}"
            else:
                interface_str = f"{interface[node][1].get('ip')}/{31}"
            network_route_dto.append({
                "segment":str(ipaddress.ip_interface(interface_str).network) ,
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
            if interf['interfaceName'] == "Loopback0":
                interface_list.append({
                    "segment": interf['ip']+"/32",
                    "nextHop": "null",
                    "deviceIp": device_ip,
                    "interfaceName": interf['interfaceName'],
                    "interfaceId": interf['interfaceId'],
                    "peerIp": interf['ip'],
                    "mask":"32",
                    "type": "PHYSICAL"
                })
            else:
                interface_list.append({
                    "segment": interf['ip'] + "/31",
                    "nextHop": "null",
                    "deviceIp": device_ip,
                    "interfaceName": interf['interfaceName'],
                    "interfaceId": interf['interfaceId'],
                    "peerIp": interf['ip'],
                    "mask": "31",
                    "type": "PHYSICAL"
                })
        edge_list=[]
        for neighbor in adjacency_list[node]:

            remote_im=policy_id.get((node,neighbor),0)
            local_im=policy_id.get((neighbor,node),0)
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
                "localDeviceName": normalize_device_name(node),
                "remoteIp": device_ips[neighbor],
                "remoteDeviceName": normalize_device_name(neighbor),
                "localPeers":local_peers,
                "remotePeers": remote_peers,
                "remoteAsNumber": str(device_as[neighbor]),
                "localAsNumber": str(device_as[node]),
                    }
            )
            edge_list.append({
                "localIp": device_ip,
                "localDeviceName":normalize_device_name(node),
                "remoteIp": device_ips[neighbor],
                "remoteDeviceName":normalize_device_name(neighbor),
                "localExportPolicyName": "null" ,
                "localExportPolicyId":0,
                "localImportPolicyName": "null" if local_im == 0 else "policy_" + str(local_im),
                "localImportPolicyId": local_im,
                "remoteExportPolicyName": "null" ,
                "remoteExportPolicyId":0,
                "remoteImportPolicyName": "null" if remote_im == 0 else "policy_" + str(remote_im),
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
def export_results(nodes=0,edges=0,execution_time=0.0,result_file="parse.txt"):
    with open(result_file, 'w') as f:
        f.write(f"Node Count: {nodes}\n")
        f.write(f"Edge Count: {edges}\n")
        f.write(f"Total Parse Time: {execution_time:.4f}S\n")

def batch_process_acorn_files(input_dir, output_dir,dest_type):
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
        print(f"创建输出目录: {output_dir}")
    print(os.listdir(input_dir))
        # 2. 匹配文件夹下所有以 reach-allsrc.in 结尾的文件
    search_pattern = os.path.join(input_dir, "*reach-allsrc.in")
    files = glob.glob(search_pattern)

    print(f"找到 {len(files)} 个待处理文件...")

    for file_path in files:
        # 提取文件名（不含路径和扩展名），用于生成输出文件名
        # 例如: hijack_2021_06_22_2_reach-allsrc.in -> hijack_2021_06_22_2
        base_name = os.path.basename(file_path).replace("_reach-allsrc.in", "")
        print(f"正在处理: {base_name} ...")
        start_time = time.time()
        acorn_data = read_acorn_input(file_path)
        device_ips = generate_device_ips(acorn_data["nodes"])
        interfaces, peers = generate_interface(device_ips ,acorn_data["adjacency_list"])
        device_as=generate_as(acorn_data["nodes"])
        if dest_type == "multiple":
            policy_output = os.path.join(output_dir, f"{base_name}_multi_policy.json")
            network_output = os.path.join(output_dir, f"{base_name}_multi_network.json")
        else:
            policy_output = os.path.join(output_dir, f"{base_name}_policy.json")
            network_output = os.path.join(output_dir, f"{base_name}_network.json")
        parse_ouput=os.path.join(output_dir, f"{base_name}_parse.txt")
        policy_id = generate_policy(acorn_data["policy"], device_as,policy_output)
        dest = []
        if dest_type=="single":
            dest.append(acorn_data["dest"])
        else:
            dest = acorn_data["nodes"]
        generate_all(acorn_data["nodes"], acorn_data["adjacency_list"], dest, device_ips, interfaces,
                     peers, policy_id,device_as, network_output)
        end_time = time.time()
        execution_time = end_time - start_time
        export_results(len(acorn_data["nodes"]), len(acorn_data["adjacency_list"]),execution_time,parse_ouput)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Run Acorn Configuration Construction"
    )
    parser.add_argument(
        "--topo",
        choices=["bgpstream", "topologyzoo"],
        required=True,
        help="topology name",
    )
    parser.add_argument(
        "--dest",
        required=True,
        default="single",
        choices=["single", "multiple"],
        help="Destination type"
    )

    args = parser.parse_args()

    topo = args.topo
    dest = args.dest

    if topo == "bgpstream":
        batch_process_acorn_files("BGPStream/input", "BGPStream/output",dest_type=dest)
    elif topo == "topologyzoo":
        batch_process_acorn_files("TopologyZoo/input", "TopologyZoo/output",dest_type=dest)
