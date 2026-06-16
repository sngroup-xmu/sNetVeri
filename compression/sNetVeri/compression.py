import glob
import json
import os
import re
import shutil
from collections import defaultdict
from collections import Counter
import time


class NetworkProcessor:
    def __init__(self, topology_type="DCN"):
        self.nodes = {}
        self.topology_type = topology_type  # 记录拓扑类型，如 "Fattree"
        self.adj = defaultdict(set)
        self.compressed_adj= defaultdict(set)
        self.raw_groups = []
        self.final_groups = []
        self.n2g=[]
        self.as_nums=[]
    def _build_adjacency(self):
        """构建邻接关系图"""
        adj = defaultdict(set)
        for d_ip, device in self.nodes.items():
            for edge in device.get('edgeList', []):
                remote_ip = edge.get('remoteIp')
                if remote_ip:
                    adj[d_ip].add(remote_ip)
                    adj[remote_ip].add(d_ip)
        return adj

    def _is_as_mixed(self, node_group):
        """判断一组节点中的 AS 号是否不一致"""
        as_set = {self.nodes[n].get('asNum') for n in node_group}
        return len(as_set) > 1

    def _determine_split_logic(self, group):
        """
        核心规则判断：确定该组是否由于 AS 不一致而必须拆分
        """
        is_mixed = self._is_as_mixed(group['nodes'])
        if not is_mixed:
            return False

        # --- 新规则：Fattree 特殊逻辑 ---
        # if self.topology_type == "Fattree":
        #     # 如果角色是 ToR，允许混合 AS（不拆分）
        #     if group.get('role') == "TOR":
        #         return False
        #     # 其余角色在 Fattree 下只要 Mixed 就必须拆分
        #     return True

        # 1. 如果被强制标记为 Strict，拆分
        if group.get('force_strict'):
            return True
        # 2. 如果没有路由配置（allow_mixed 为 False），拆分
        if not group.get('allow_mixed'):
            return True

        return False

    def _subgroup(self):
        """执行分层剥离和冲突检查"""
        processed = set()
        iteration = 0

        while len(processed) < len(self.nodes):
            current_layer = [n for n in self.nodes if n not in processed]
            if not current_layer: break

            curr_degrees = {n: len([nbr for nbr in self.adj[n] if nbr not in processed]) for n in current_layer}
            min_deg = min(curr_degrees.values())
            nodes_to_check = [n for n, d in curr_degrees.items() if d == min_deg]

            # 按指纹聚类
            fp_groups = defaultdict(list)
            for n in nodes_to_check:
                fp = self._get_node_fingerprint(n, processed)
                fp_groups[fp].append(n)

            for fp, nodes_in_fp in fp_groups.items():
                for cluster in self._split_by_connectivity(nodes_in_fp):
                    parents = {nbr for n in cluster for nbr in self.adj[n]
                               if nbr not in processed and nbr not in cluster}
                    if self.topology_type == "DCN":
                        self.raw_groups.append({
                            'id': len(self.raw_groups),
                            'nodes': cluster,
                            'parents': parents,
                            'allow_mixed': (fp[0]=="TOR"),
                            'level': iteration,
                            'force_strict': False,
                            'role': fp[0]
                        })
                    else:
                        self.raw_groups.append({
                            'id': len(self.raw_groups),
                            'nodes': cluster,
                            'parents': parents,
                            'allow_mixed': (iteration % 2 == 0),
                            'level': iteration,
                            'force_strict': False,
                            'role': fp[0]
                        })

            for n in nodes_to_check: processed.add(n)
            iteration += 1
        self.n2g = {n: g['id'] for g in self.raw_groups for n in g['nodes']}
        self._check_as_conflicts()
        self.final_groups = []
        current_final_id = 1

        for g in self.raw_groups:
            should_split = self._determine_split_logic(g)

            if should_split:
                # 按 AS 拆分
                as_map = defaultdict(list)
                for n in g['nodes']:
                    as_map[self.nodes[n].get('asNum')].append(n)

                # 为拆分后的每一组创建新的 Group 字典
                for sub_nodes in as_map.values():
                    new_group = g.copy()  # 浅拷贝原属性 (role, parents, level 等)
                    new_group['id'] = current_final_id
                    new_group['nodes'] = sub_nodes
                    # 注意：parents 暂时保持原样，通常不需要重新计算父子关系

                    self.final_groups.append(new_group)
                    current_final_id += 1
            else:
                # 不拆分，直接加入，但更新 ID
                g['id'] = current_final_id
                self.final_groups.append(g)
                current_final_id += 1

        # --- 最后更新全局 n2g 映射 ---
        self.n2g = {n: g['id'] for g in self.final_groups for n in g['nodes']}

    def _check_as_conflicts(self):
        """标记as冲突"""
        for U in self.raw_groups:

            current_u_as_set = {self.nodes[n].get('asNum') for n in U['nodes']}
            # 如果当前组中有任何一个 AS 号在指定的 as_nums 中，强制要求一致（即不允许 Mixed）
            if not current_u_as_set.isdisjoint(self.as_nums):
                # 只有当它目前还是 Mixed 状态时，才需要强制拆分
                if self._is_as_mixed(U['nodes']):
                    U['force_strict'] = True
            if not self._is_as_mixed(U['nodes']): continue

            impacted_v_ids = {self.n2g[p] for p in U['parents'] if p in self.n2g}
            for v_id in impacted_v_ids:
                V = self.raw_groups[v_id]
                if self._is_as_mixed(V['nodes']):
                    if len(U['nodes']) <= len(V['nodes']):
                        U['force_strict'] = True
                    else:
                        V['force_strict'] = True
                else:
                    V['force_strict'] = True

    def _export_results(self, result_file="compression_result.txt",execution_time=0.0):
        final_group_count = []
        final_clusters=[]
        """应用拆分规则并导出"""
        core=[]
        agg=[]
        tor=[]
        router=[]
        edge_count = sum(len(neighbors) for neighbors in self.adj.values())
        compressed_edge_count = sum(len(neighbors) for neighbors in self.compressed_adj.values())
        for clusters in self.final_groups:
            role = clusters.get("role")
            final_group_count.append(role)
            # as_status = "Consistent" if not self._is_as_mixed(clusters.get("nodes")) else "Mixed"
            # ips = clusters.get("nodes")
            # if role == "TOR":
            #     tor.append({
            #         "id": len(tor)+1,
            #         "as_status": as_status,
            #         "deviceIp":ips
            #     })
            # elif role == "CORE":
            #     core.append({
            #         "id": len(core)+1,
            #         "as_status": as_status,
            #         "deviceIp":ips
            #     })
            # elif role == "AGGREGATION":
            #     agg.append({
            #         "id": len(agg)+1,
            #         "as_status": as_status,
            #         "deviceIp":ips
            #     })
            # else:
            #     router.append({
            #         "id": len(router)+1,
            #         "as_status": as_status,
            #         "deviceIp":ips
            #     })
        with open(result_file, 'w') as f:
            role_stats = Counter(final_group_count)
            f.write("\n" + "=" * 30+"\n")
            f.write(f"{'Role':<15} | {'Group Count':<10}\n")
            f.write("-" * 30+"\n")
            for role, count in sorted(role_stats.items()):
                f.write(f"{role:<15} | {count:<10}\n")
            f.write("=" * 30+"\n")
            f.write(f"Node Count: {len(self.nodes)}\n")
            f.write(f"Edge Count:{edge_count}\n")
            f.write(f"Total Groups: {len(final_group_count)}\n")
            f.write(f"Total Compressed Edge:{compressed_edge_count}\n")
            f.write(f"Compression Time: {execution_time:.4f}s\n")
        # if self.topology_type=="WAN":
        #     with open("wan.txt", 'w', encoding="utf-8") as f:
        #         json.dump(router, f, indent=4, ensure_ascii=False)
        # else:
        #     with open("Core-zmr.txt", "w", encoding="utf-8") as outfile:
        #         json.dump(core, outfile, indent=4, ensure_ascii=False)
        #     with open("Aggregation-zmr.txt", "w", encoding="utf-8") as outfile:
        #         json.dump(agg, outfile, indent=4, ensure_ascii=False)
        #     with open("Tor-zmr.txt", "w", encoding="utf-8") as outfile:
        #         json.dump(tor, outfile, indent=4, ensure_ascii=False)
        self.final_groups=final_clusters
    # --- 辅助函数 (保持内部简洁) ---
    def _has_route_configs(self, node_name):
        vrfs = self.nodes.get(node_name, {}).get('vrfMap', [])
        return any(v.get('networkRouteList', {}).get('NetworkRouteDto') or
                   v.get('importRouteList', {}).get('ImportRouteDto') for v in vrfs)

    def _get_node_fingerprint(self, node_name, processed):
        """生成节点指纹用于同质化分组"""
        data = self.nodes.get(node_name, {})
        # 邻居指纹：剔除已处理的节点
        curr_neighbors = tuple(sorted([n for n in self.adj[node_name] if n not in processed]))
        # 角色特征
        role_feat = data.get('deviceRole', 'UNKNOWN')
        device_group = data.get('deviceGroup', "null")
        region = data.get('region', "null")
        # 策略特征
        policies = []
        for edge in data.get('edgeList', []):
            policies.append((edge.get('localExportPolicyId'),edge.get('localImportPolicyId'), edge.get('remoteImportPolicyId'),edge.get('remoteExportPolicyId'),edge.get('remoteIp')))
        # 路由聚合特征
        routes = []
        preference=[]
        for vrf in data.get('vrfMap', []):
            max_lb_num=vrf.get('maxLbNum')
            lb_as_path_relax=vrf.get('lbAsPathRelax')
            preference_external=vrf['preferenceExternal']
            preference_internal=vrf['preferenceInternal']
            preference_local=vrf['preferenceLocal']
            preference.append((max_lb_num,lb_as_path_relax,preference_external, preference_internal, preference_local))
            agg_list = vrf.get('aggregateRouteList', {}).get('AggregateRouteDto', [])
            for r in agg_list:
                routes.append((r.get('segment'), r.get('attributePolicyId'), r.get('suppressPolicyId'),r.get('detailSuppress')))

        return role_feat, curr_neighbors, tuple(sorted(preference)),tuple(sorted(policies)), tuple(sorted(routes)),region,device_group

    def _split_by_connectivity(self, nodes):
        sub_groups = []
        for n in nodes:
            found = False
            for sg in sub_groups:
                if not any(nbr in sg for nbr in self.adj[n]):
                    sg.append(n);
                    found = True;
                    break
            if not found: sub_groups.append([n])
        return sub_groups

    def _get_as_numbers(self,policy_data):
        as_nums=set()
        for node in policy_data:
            policys = node.get("policy", {})
            if policys == []:
                continue
            for dto in policys:
                match_conditions = dto.get("matchConditions", [])
                for condition in match_conditions:
                    if condition.get("type") == "as_path_filter":
                        # 提取具体的 policy 字符串内容
                        raw_policy_str = str(condition.get("policy", ""))

                        # 预处理：替换特殊字符为空格
                        clean_policy = re.sub(r'[_^$]', ' ', raw_policy_str)

                        # 匹配数字 (例如: 65001)
                        numeric_matches = re.findall(r"\b\d+\b", clean_policy)
                        # 匹配 X.Y 格式 (例如: 1.23)
                        x_y_matches = re.findall(r"\b\d+\.\d+\b", clean_policy)
                        as_nums.update(numeric_matches)
                        as_nums.update(x_y_matches)
        return as_nums
    def _compress(self,output_file):
        results=[]
        self.compressed_adj=defaultdict(set)
        group_by_id={d['id']: d for d in self.final_groups}
        for cluster in self.final_groups:
            device_name=cluster["role"]+str(cluster["id"])
            device_ips=cluster["nodes"]
            device_role=cluster["role"]
            node0=self.nodes[cluster["nodes"][0]]
            region=node0["region"]
            logi_area=node0["logiArea"]
            device_group=node0["deviceGroup"]
            if not self._is_as_mixed(device_ips):
                as_num=node0["asNum"]
            else:
                as_num="0."+str(cluster["id"])
            physical_node_list=[]
            edge_list=[]
            nbr_id=set()
            for device_ip in cluster["nodes"]:
                node=self.nodes[device_ip]
                peer_list=[]
                for edge in node["edgeList"]:
                    for peer in edge["peerList"]:
                        local_peer_ip=peer["localPeers"][0]["peerIp"]
                        local_peer_interface=peer["localPeers"][0].get("interfaceName")
                        remote_peer_ip=peer["remotePeers"][0]["peerIp"]
                        remote_peer_interface=peer["remotePeers"][0].get("interfaceName")
                        peer_list.append({
                            "localIp":peer["localIp"],
                            "remoteIp":peer["remoteIp"],
                            "remoteAsNumber":peer["remoteAsNumber"],
                            "localAsNumber":peer["localAsNumber"],
                            "localPeerIp": local_peer_ip,
                            "remotePeerIp": remote_peer_ip,
                            "localInterfaceName": local_peer_interface,
                            "remoteInterfaceName": remote_peer_interface,
                        })
                    remote_id=self.n2g[edge["remoteIp"]]
                    if remote_id  not in nbr_id:
                        edge_list.append({
                            "localIp": device_ips,
                            "remoteIp": group_by_id[remote_id]["nodes"],
                            "localExportPolicyId": edge["localExportPolicyId"],
                            "localImportPolicyId": edge["localImportPolicyId"],
                            "remoteExportPolicyId": edge["remoteExportPolicyId"],
                            "remoteImportPolicyId": edge["remoteImportPolicyId"],
                            "remoteNodeId": remote_id
                        })
                        nbr_id.add(remote_id)
                vrfmap=node["vrfMap"][0]
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
            for nbr in nbr_id:
                self.compressed_adj[cluster["id"]].add(nbr)
                self.compressed_adj[nbr].add(cluster["id"])
            results.append({
                "NodeId": cluster["id"],
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
        with open(output_file, "w", encoding="utf-8") as outfile:
            json.dump(results, outfile, indent=4, ensure_ascii=False)
    def process(self,network_file,policy_file,output_path):
        start_time = time.time()
        # compressed_dir = os.path.join(output_path, "Compressed")
        #
        # # 2. 确保该文件夹及其父目录存在
        # if not os.path.exists(compressed_dir):
        #     os.makedirs(compressed_dir, exist_ok=True)
        with open(network_file, "r", encoding="utf-8") as infile:
            network_data = json.load(infile)
        with open(policy_file, "r", encoding="utf-8") as infile:
            policy_data = json.load(infile)
        self.as_nums=self._get_as_numbers(policy_data)
        self.nodes = {d['deviceIp']: d for d in network_data}
        self.adj=self._build_adjacency()
        self.raw_groups = []
        self.final_groups = []
        self.compressed_adj=defaultdict(set)
        self.n2g = []
        self._subgroup()
        base_name = os.path.basename(network_file)
        name, ext = os.path.splitext(base_name)
        # network_name=name.replace("_network","_compressed_network")
        output_file = os.path.join(output_path, f"{name}{ext}")
        results_file = os.path.join(output_path, f"{name}_info.txt")
        self._compress(output_file=output_file)
        end_time = time.time()
        execution_time = end_time - start_time
        self._export_results(result_file=results_file,execution_time=execution_time)


def run_batch_processing():
    # 1. 定义配置映射：目录路径 -> 对应的拓扑类型
    # 注意：这里建议使用相对路径或绝对路径，确保能找到文件
    config_map = {
        "../benchmarks/Acorn/BGPStream/output/": "WAN",
        "../benchmarks/Acorn/TopologyZoo/output/": "WAN",
        "../benchmarks/Fattree/output/": "DCN"
    }
    script_dir = os.path.dirname(os.path.abspath(__file__))
    base_output_root = os.path.join(script_dir, "compression")

    for folder_path, topo_type in config_map.items():
        print(f"\n检查目录: {folder_path}")

        if not os.path.exists(folder_path):
            continue
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
            processor = NetworkProcessor(topology_type=topo_type)

            for net_file in network_files:
                network_name = os.path.basename(net_file).replace("_network.json", "")
                dataset_output_dir = os.path.join(base_output_root, second_level_dir)

                os.makedirs(dataset_output_dir, exist_ok=True)
                # 当前 network 独立目录
                network_output_dir = os.path.join(dataset_output_dir, network_name)
                os.makedirs(network_output_dir, exist_ok=True)
                # 确定 Policy 文件路径
                policy_file = net_file.replace("_network.json", "_policy.json")
                if "Fattree" in folder_path:
                     policy_file = "../benchmarks/Fattree/output/fattree_policy.json"
                if os.path.exists(policy_file):
                    # --- 执行复制 policy.json 到目标输出目录 ---
                    target_policy_path = os.path.join(network_output_dir, "policy.json")
                    shutil.copy2(policy_file, target_policy_path)
                    print(f"  处理: {os.path.basename(net_file)}")
                    print(f"  复制 Policy 至: {target_policy_path}")
                target_topo_path = os.path.join(network_output_dir, "topo.json")
                shutil.copy2(net_file, target_topo_path)
                try:
                    # 执行处理，输出到新创建的目录
                    processor.process(
                        network_file=target_topo_path,
                        policy_file=policy_file,
                        output_path=network_output_dir
                    )
                except Exception as e:
                    print(f"  错误: {str(e)}")
                    print(f"  复制 Policy 至: {target_policy_path}")


if __name__ == '__main__':
    run_batch_processing()
