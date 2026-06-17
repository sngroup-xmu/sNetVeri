import argparse
import json
import os
import re
import shutil
import sys


def _dataset_name(network_file):
    network_file = os.path.normpath(network_file)
    path_parts = network_file.split(os.sep)
    if "Acorn" in path_parts:
        acorn_index = path_parts.index("Acorn")
        if len(path_parts) > acorn_index + 1:
            return path_parts[acorn_index + 1]
    if "Fattree" in path_parts:
        return "Fattree"
    if len(path_parts) > 2:
        return path_parts[2]
    if len(path_parts) < 2:
        return path_parts[0]
    return path_parts[-2]


def change_format(network_file, output_root, network_type):
    network_file = os.path.normpath(network_file)
    second_level_dir = _dataset_name(network_file)
    base_name = os.path.basename(network_file)
    network_name = base_name.replace("_network.json", "")

    dataset_output_dir = os.path.join(output_root, second_level_dir)
    network_output_dir = os.path.join(dataset_output_dir, network_name)
    os.makedirs(network_output_dir, exist_ok=True)

    with open(network_file, "r", encoding="utf-8") as infile:
        network_data = json.load(infile)
    nodes = {d['deviceIp']: {"id": i, **d} for i, d in enumerate(network_data)}
    results = []
    for idx, node in nodes.items():
        device_name = node["deviceName"]
        device_ips = [node["deviceIp"]]
        device_role =node["deviceRole"]
        region = node["region"]
        logi_area = node["logiArea"]
        device_group = node["deviceGroup"]
        as_num = node["asNum"]
        physical_node_list = []
        edge_list = []
        peer_list = []
        for edge in node["edgeList"]:
            for peer in edge["peerList"]:
                local_peer_ip = peer["localPeers"][0]["peerIp"]
                local_peer_interface = peer["localPeers"][0].get("interfaceName")
                remote_peer_ip = peer["remotePeers"][0]["peerIp"]
                remote_peer_interface = peer["remotePeers"][0].get("interfaceName")
                peer_list.append({
                    "localIp": peer["localIp"],
                    "remoteIp": peer["remoteIp"],
                    "remoteAsNumber": peer["remoteAsNumber"],
                    "localAsNumber": peer["localAsNumber"],
                    "localPeerIp": local_peer_ip,
                    "remotePeerIp": remote_peer_ip,
                    "localInterfaceName": local_peer_interface,
                    "remoteInterfaceName": remote_peer_interface,
                })
            edge_list.append({
                "localIp": device_ips,
                "remoteIp": [edge["remoteIp"]],
                "localExportPolicyId": edge["localExportPolicyId"],
                "localImportPolicyId": edge["localImportPolicyId"],
                "remoteExportPolicyId": edge["remoteExportPolicyId"],
                "remoteImportPolicyId": edge["remoteImportPolicyId"],
                "remoteNodeId": nodes[edge["remoteIp"]]["id"],
            })
        vrfmap = node["vrfMap"][0]
        physical_node_list.append({
            "deviceIp": node["deviceIp"],
            "asNum": node["asNum"],
            "routerId": node["routerId"][0],
            "interfaceList": node["interfaceList"],
            "peerList": peer_list,
            "vrfName": vrfmap["vrfName"],
            "maxLbNum": vrfmap["maxLbNum"],
            "lbAsPathRelax": vrfmap["lbAsPathRelax"],
            "preferenceExternal": vrfmap["preferenceExternal"],
            "preferenceInternal": vrfmap["preferenceInternal"],
            "preferenceLocal": vrfmap["preferenceLocal"],
            "NetworkRouteDto": vrfmap["networkRouteList"]["NetworkRouteDto"],
            "ImportRouteDto": vrfmap["importRouteList"]["ImportRouteDto"],
            "AggregateRouteDto": vrfmap["aggregateRouteList"]["AggregateRouteDto"],
        })

        results.append({
            "NodeId": node["id"],
            "deviceName": device_name,
            "deviceIp": device_ips,
            "deviceRole": device_role,
            "region": region,
            "logiArea": logi_area,
            "deviceGroup": device_group,
            "asNum": as_num,
            "physicalNodeList": physical_node_list,
            "edgeList": edge_list
        })

    output_file = os.path.join(network_output_dir, "topo.json")
    if network_type == "bonsai":
        policy_file = re.sub(r"_bonsai.*", "_policy.json", network_file)
    else:
        policy_file = re.sub(r"_network.*", "_policy.json", network_file)
    if "Fattree" in network_file:
        policy_file = "../benchmarks/Fattree/output/fattree_policy.json"
    if os.path.exists(policy_file):
        target_policy_path = os.path.join(network_output_dir, "policy.json")
        shutil.copy2(policy_file, target_policy_path)

    with open(output_file, "w", encoding="utf-8") as outfile:
        json.dump(results, outfile, indent=4, ensure_ascii=False)

    info_file = os.path.join(network_output_dir, "topo_info.txt")
    with open(info_file, "w", encoding="utf-8") as f:
        f.write(f"Node Count: {len(results)}\n")
        f.write(f"Source File: {os.path.basename(network_file)}\n")
        f.write("Compression: disabled\n")

def find_all_network_files(input_dir):
    """
    递归查找所有 *_network.json
    """
    network_files = []
    for root, _, files in os.walk(input_dir):
        for file in files:
            if file.endswith("_network.json"):
                network_files.append(os.path.join(root, file))
    return network_files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Run sNetVeri Configuration Construction"
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to topology json file",
    )
    parser.add_argument(
        "--type",
        required=True,
        default="benchmarks",
        choices=["bonsai", "benchmarks"],
        help="Network type"
    )

    args = parser.parse_args()

    input_dir = args.input
    network_type = args.type
    network_files = find_all_network_files(input_dir)

    if not network_files:
        print("No *_network.json files found")
        sys.exit(0)

    print(f"Found {len(network_files)} network files\n")

    for network_file in network_files:
        change_format(network_file, f"./wo_compression/{network_type}", network_type)
