import argparse
import glob
import json
import os
import random
import shutil
from collections import defaultdict
import time


def get_local_pref_count(nodes,node_ips,polices):
    """
    函数插件：统计该节点 VRF 配置中 localPreference 的种类数
    您可以根据实际 JSON 深度调整提取逻辑
    """
    policy_id=set()
    for ip in node_ips:
        node=nodes[ip]
        for edge in node.get("edgeList"):
            policy_id.add(edge.get("localExportPolicyId"))
            policy_id.add(edge.get("localImportPolicyId"))
        local_pref_count = set()
        for id in policy_id:
            policy=polices.get(id)
            po=policy["policy"]
            if po:
                for p in po:
                    local_pref_count.add(p.get("ApplyActionDto").get("localPref"))
            else:
                local_pref_count.add("null")
    return len(local_pref_count)

def get_network(input_file):
    with open(input_file, "r") as f:
        network = {node['deviceIp']: node  for node in json.load(f)}
    return network

def get_policy(input_file):
    with open(input_file, "r") as f:
        policy = {po['id']: po   for po in json.load(f)}
    return policy

def get_destination(nodes):
    dest_nodes=[]
    for n in nodes:
        node = nodes[n]
        has_network = any(v.get("networkRouteList", {}).get("NetworkRouteDto") for v in node.get("vrfMap", []))
        has_import = any(v.get("importRouteList", {}).get("ImportRouteDto") for v in node.get("vrfMap", []))

        if has_network or has_import:
            dest_nodes.append(n)
    return dest_nodes


def compress_topology(nodes, dest_nodes_ips, polices):
    """
    nodes: dict, key 是 IP, value 是节点信息
    dest_nodes_ips: list, 目的地 IP 列表
    polices: dict, 策略映射表
    """
    compressible_nodes = {}

    for dest in dest_nodes_ips:
        class_counter = 1
        # 初始化：node_to_class 记录所有节点的分类
        node_to_class = {ip: "WO/DEST" for ip in nodes.keys()}
        node_to_class[dest] = "DEST"

        # 待处理节点列表：初始为除 dest 以外的所有点
        nodes_ips = [ip for ip in nodes.keys() if ip != dest]

        # 存储最终确定的压缩结果
        final_compression_result = defaultdict(list)
        final_compression_result["DEST"].append(dest)

        refined = True
        i=0
        while refined and nodes_ips:
            i+=1
            # print(i)
            # signature_map: signature -> [node_ips]
            signature_map = defaultdict(list)
            lp_count = get_local_pref_count(nodes,nodes_ips,polices)
            # 1. 计算当前 nodes_ips 中每个节点的特征签名
            for node_ip in nodes_ips:
                node = nodes[node_ip]
                features = []
                vrf_map=node.get("vrfMap", [])
                routes = []
                preference = []
                for vrf in vrf_map:
                    max_lb_num = vrf.get('maxLbNum')
                    lb_as_path_relax = vrf.get('lbAsPathRelax')
                    preference_external = vrf['preferenceExternal']
                    preference_internal = vrf['preferenceInternal']
                    preference_local = vrf['preferenceLocal']
                    preference.append(
                        (max_lb_num, lb_as_path_relax, preference_external, preference_internal, preference_local))
                    agg_list = vrf.get('aggregateRouteList', {}).get('AggregateRouteDto', [])
                    for r in agg_list:
                        routes.append((r.get('segment'), r.get('attributePolicyId'), r.get('suppressPolicyId'),
                                       r.get('detailSuppress')))

                for edge in node.get("edgeList", []):
                    policy = (edge.get("localExportPolicyId"), edge.get("remoteImportPolicyId"))
                    remote_ip = edge.get("remoteIp")

                    # 核心逻辑：LP > 1 邻居看真实IP，否则看压缩类别
                    if lp_count > 1:
                        neighbor = remote_ip
                    else:
                        neighbor = node_to_class.get(remote_ip, "UNKNOWN")

                    features.append((tuple(sorted(preference)),tuple(sorted(routes)),neighbor, policy))

                signature = frozenset(sorted(features))
                signature_map[signature].append(node_ip)

            if not signature_map:
                break

                # 找到成员数量最多的签名
            max_sig = max(signature_map.keys(), key=lambda k: len(signature_map[k]))
            max_group_members = signature_map[max_sig]

            # 2. 更新 node_to_class
            # 为本轮产生的所有组分配新的 Label
            for sig, members in signature_map.items():
                class_label = f"CLASS_{class_counter}"
                class_counter += 1
                for m in members:
                    node_to_class[m] = class_label

                # 如果不是最大组，直接判定为“已确定”，移入最终结果
                if sig != max_sig:
                    final_compression_result[class_label].extend(members)

            # 3. 决定下一轮要细化的 nodes_ips
            # 只有最大组的成员会进入下一轮迭代，看是否会被进一步拆分
            if len(max_group_members) == len(nodes_ips):
                # 如果最大组的大小等于当前待处理列表的大小，说明无法再细分了
                final_label = node_to_class[max_group_members[0]]
                final_compression_result[final_label].extend(max_group_members)
                refined = False
                nodes_ips = []
            else:
                # 只保留最大组
                nodes_ips = max_group_members
                refined = True

        compressible_nodes[dest] = dict(final_compression_result)

    return compressible_nodes


def print_compressed_results(compressible_nodes, output_dir="./"):
    """
    打印函数：将结果保存为 dest_ip.txt 格式的 JSON 文件
    """
    # 如果目录不存在则创建
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    for dest_ip, result in compressible_nodes.items():
        # 构建文件名，例如 25.54.27.5.txt
        file_name = f"{dest_ip}.txt"
        file_path = os.path.join(output_dir, file_name)

        # 按照格式要求整理 JSON 内容
        output_data = {
            "destination": dest_ip,
            "compression_details": result,
            "total_classes": len(result)
        }

        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(output_data, f, indent=4, ensure_ascii=False)

        print(f"已生成文件: {file_path}")

def generate_compressed_topology(nodes, compression_result,name,output_dir="output"):
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    compression_edges={}
    for dest, members in compression_result.items():
        compressed_nodes_ip = []
        compressed_nodes = []
        compression_edges[dest]=0
        for group,member in members.items():
            compressed_nodes_ip.append(member[0])
        for index,ip in enumerate(compressed_nodes_ip):
            node = nodes[ip]
            vrf_map = node.get("vrfMap", [])
            compressed_vrf_map = []
            for vrf in vrf_map:
                if dest == vrf.get("deviceIp"):
                    network_route_dto=vrf.get("networkRouteList", {}).get("NetworkRouteDto", [])
                    import_route_dto=vrf.get("importRouteList", {}).get("ImportRouteDto", [])
                else:
                    network_route_dto=[]
                    import_route_dto=[]
                compressed_vrf_map.append(
                      {
                    "deviceIp": vrf.get("deviceIp"),
                    "vrfName": vrf.get("vrfName"),
                    "maxLbNum": vrf.get("maxLbNum"),
                    "lbAsPathRelax": vrf.get("lbAsPathRelax"),
                    "preferenceExternal": vrf["preferenceExternal"],
                    "preferenceInternal": vrf["preferenceInternal"],
                    "preferenceLocal": vrf["preferenceLocal"],
                    "networkRouteList": {
                        "NetworkRouteDto": network_route_dto
                    },
                    "importRouteList": {
                        "ImportRouteDto": import_route_dto
                    },
                    "aggregateRouteList": {
                        "AggregateRouteDto": vrf.get("aggregateRouteList", {}).get("AggregateRouteDto", [])
                    }
                })
            compressed_edges=[]
            for edge in node.get("edgeList", []):
                if edge.get("remoteIp") in compressed_nodes_ip:
                    compressed_edges.append(edge)
            compression_edges[dest]+= len(compressed_edges)
            compressed_node={
            "deviceName": node.get("deviceName"),
            "deviceIp": node.get("deviceIp"),
            "deviceRole": node.get("deviceRole"),
            "deviceGroup": node.get("deviceGroup"),
            "region": node.get("region"),
            "logiArea": node.get("logiArea"),
            "routerId":node.get("routerId"),
            "asNum":node.get("asNum"),
            "routePolicy": node.get("routePolicy"),
            "vrfMap": compressed_vrf_map,
            "interfaceList": node.get("interfaceList"),
            "edgeList":  compressed_edges
            }
            compressed_nodes.append(compressed_node)
        output_path = os.path.join(output_dir, name+"_bonsai_"+str(dest).replace('.','_')+"_network.json")
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(compressed_nodes, f, indent=4, ensure_ascii=False)
    return compression_edges

def export_results(compressible_nodes,compressed_edges,name,result_dir="compression_info.txt", execution_time_io=0.0, excution_time_all=0.0):
    result_file=os.path.join(result_dir,name+"_info.txt")
    with open(result_file, 'w') as f:
        for dest, members in compressible_nodes.items():
            f.write(f"Dest:{dest}\n")
            f.write(f"Total Groups: {len(members)}\n")
            f.write(f"Total Compressed Edge:{compressed_edges[dest]}\n")
        f.write(f"Input I/O Time: {execution_time_io:.4f}s\n")
        f.write(f"Total Compression Time: {excution_time_all}\n")

def run_batch_processing(input_dir,count):
    # 1. 定义配置映射：目录路径 -> 对应的拓扑类型
    # 注意：这里建议使用相对路径或绝对路径，确保能找到文件
    script_dir = os.path.dirname(os.path.abspath(__file__))
    base_output_root = os.path.join(script_dir, ".")
    folder_path=input_dir
    print(f"\n检查目录: {folder_path}")

    if not os.path.exists(folder_path):
        return
    folder_path = os.path.normpath(folder_path)
    path_parts = folder_path.split(os.sep)
    if len(path_parts) < 2:
        second_level_dir = path_parts[0]
    elif path_parts[2] == "Acorn":
        second_level_dir = path_parts[3]
    else:
        second_level_dir = path_parts[2]
    # 在当前目录下创建 output/目录名
    target_output_dir = os.path.join(base_output_root, second_level_dir)
    if not os.path.exists(target_output_dir):
        os.makedirs(target_output_dir)

    # 3. 查找网络文件
    search_pattern = os.path.join(folder_path, "*_network.json")
    network_files = glob.glob(search_pattern)
    if network_files:
        for net_file in network_files:
            base_name = os.path.basename(net_file)
            name, ext = os.path.splitext(base_name)
            # output_dir=os.path.join(target_output_dir, name.replace("_network", ""))
            # 确定 Policy 文件路径
            policy_file = net_file.replace("_network.json", "_policy.json")
            if "Fattree" in folder_path:
                 policy_file = "../benchmarks/Fattree/output/fattree_policy.json"
            if os.path.exists(policy_file):
                # --- 执行复制 policy.json 到目标输出目录 ---
                target_policy_path = os.path.join(target_output_dir, os.path.basename(policy_file))
                shutil.copy2(policy_file, target_policy_path)

                # print(f"  处理: {os.path.basename(net_file)}")
                # print(f"  复制 Policy 至: {target_policy_path}")
            if os.path.exists(policy_file):
                start_time1=time.time()
                network = get_network(net_file)
                polices = get_policy(policy_file)
                dest_nodes= get_destination(nodes=network)
                if len(dest_nodes)>count:
                    dest_nodes_ips = random.sample(dest_nodes, count)
                else:
                    dest_nodes_ips=dest_nodes
                    print(f"dest count: {len(dest_nodes)}")
                end_time1 = time.time()
                start_time2 = time.time()
                try:
                    compressible_nodes = compress_topology(network, dest_nodes_ips, polices)
                    compression_edges=generate_compressed_topology(network, compressible_nodes,  name.replace("_network", ""),output_dir=target_output_dir)
                except Exception as e:
                    print(f"  错误: {str(e)}")
                end_time2=time.time()
                execution_time1 = end_time1 - start_time1
                execution_time2 = end_time2 - start_time2
                export_results(compressible_nodes,compression_edges,name,target_output_dir, execution_time_io=execution_time1, excution_time_all=execution_time2)
if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Run Bonsai Compression"
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to snapshot directory"
    )

    parser.add_argument(
        "--count",
        required=True,
        help="count of compressed network"
    )

    args = parser.parse_args()
    input_dir = args.input
    count = int(args.count)

    run_batch_processing(input_dir, count)
    # network=get_network("../benchmarks/HUAWEI/output/hw_network.json")
    # polices=get_policy("../benchmarks/HUAWEI/output/hw_policy.json")
    # dest_nodes_ips =get_destination(nodes=network)
    # # dest_nodes_ips = ["25.43.16.2"]
    # compressible_nodes=compress_topology(network,dest_nodes_ips,polices )
    # # print_compressed_results(compressible_nodes,output_dir="./output")
    # generate_compressed_topology(network,compressible_nodes,output_dir="./output/HUAWEI")
