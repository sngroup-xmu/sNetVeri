import argparse
import json
import os
import re
import sys
import time


# def generate_ios_policies(policies_data):
#     ios_configs_dict = {}
#
#     for p_id, item in policies_data.items():
#         p_name = item.get("name")
#         nodes = item.get("policy", [])
#
#         resource_definitions = []
#         policy_body = []
#
#         seq = 10
#         for node in nodes:
#             node_id = node.get("node")
#             action_type = "permit" if node.get("rule") == "permit" else "deny"
#
#             matches = node.get("matchConditions", [])
#             actions = node.get("ApplyActionDto", {})
#
#             match_clauses = []
#             for idx, cond in enumerate(matches):
#                 m_type, m_val = cond["type"], cond["policy"]
#                 res_name = f"{p_name}_{p_id}_{node_id}_{idx}"
#
#                 if m_type == "ip_prefix_filter":
#                     # 替换 * 和 ~ 符号为 ge/le
#                     # IOS prefix-list 只支持 ge/le 语法
#                     pl_line = m_val.replace("*", " ge ").replace("~", " le ")
#                     resource_definitions.append(f"ip prefix-list {res_name} seq 5 permit {pl_line}")
#                     match_clauses.append(f"match ip address prefix-list {res_name}")
#
#                 elif m_type == "as_path_filter":
#                     # IOS 需要 AS-PATH ACL
#                     resource_definitions.append(f"ip as-path access-list {res_name} permit {m_val}")
#                     match_clauses.append(f"match as-path {res_name}")
#
#                 elif m_type == "ip_community_filter":
#                     # IOS route-map 直接在 set community 中使用
#                     comms = m_val.replace(" ", ",")
#                     resource_definitions.append(f"! community {res_name}: {comms}")
#                     # IOS route-map 中匹配 community 需要 route-map match community，略可忽略
#
#             # 生成 route-map 条目
#             policy_body.append(f"route-map {p_name} permit {seq}")
#             seq += 10
#
#             # 添加 match 条件
#             for clause in match_clauses:
#                 policy_body.append(f"  {clause}")
#
#             # --- 处理 AS-PATH 操作 ---
#             as_val = actions.get("asPath", "").strip()
#             as_op = actions.get("asPathOperation")
#
#             if as_op == "additive" and as_val:
#                 # IOS 只支持 prepend
#                 as_list = as_val.split()
#                 policy_body.append(f"  set as-path prepend {' '.join(as_list)}")
#
#             # --- 其他属性 ---
#             if actions.get("locPrf") != "null":
#                 policy_body.append(f"  set local-preference {actions['locPrf']}")
#
#             med_val = actions.get("med")
#             if med_val and med_val != "null":
#                 policy_body.append(f"  set metric {med_val}")
#
#             comm_val = actions.get("communityValue")
#             if comm_val and comm_val != "null":
#                 comm_val = comm_val.replace("additive", "").strip()
#                 vals = comm_val.split()
#                 policy_body.append(f"  set community {' '.join(vals)}")
#
#             # deny/pass
#             policy_body.append(f"  {action_type}")
#             policy_body.append("!")  # IOS 条目结束
#
#         # 汇总配置
#         final_config = "!\n" + "\n".join(list(dict.fromkeys(resource_definitions))) + "\n!\n"
#         final_config += "\n".join(policy_body) + "\n!"
#         ios_configs_dict[str(p_id)] = final_config
#
#     return ios_configs_dict
#
#
# def generate_ios_topology(network_data, policy_configs, policy_data):
#     """
#     将原来的 generate_ios_xr_topology 改成生成 IOS 风格配置
#     """
#     ios_configs = {}
#
#     for device in network_data:
#         hostname = device.get("deviceName")
#         as_num = device.get("asNum")
#         router_id = device.get("routerId", ["0.0.0.0"])[0]
#
#         config = []
#         config.append(f"!! --- Device: {hostname} ---")
#         config.append(f"hostname {hostname}")
#
#         # 接口配置
#         for iface in device.get("interfaceList", []):
#             name = iface.get("interfaceName")
#             ip_mask = iface.get("segment")
#             type = iface.get("type")
#             if ip_mask and ip_mask != "null":
#                 config.append(f"interface {name}")
#                 # IOS 需要 A.B.C.D M.M.M.M 格式
#                 # if "/" in ip_mask:
#                 #     # ip, mask_len = ip_mask.split("/")
#                 #     # mask = (0xffffffff ^ (2**(32-int(mask_len)) - 1))
#                 #     # mask_str = ".".join([str((mask >> 24) & 0xFF),
#                 #     #                      str((mask >> 16) & 0xFF),
#                 #     #                      str((mask >> 8) & 0xFF),
#                 #     #                      str(mask & 0xFF)])
#                 #     config.append(f" ip address {ip} {mask_str}")
#                 # else:
#                 #     config.append(f" ip address {ip_mask}")
#                 # config.append(" no shutdown")
#                 config.append(f" ip address {ip_mask}")
#                 config.append("!")
#
#         # 静态路由
#         for iface in device.get("interfaceList", []):
#             if iface.get("type") == "STATIC":
#                 ip_mask = iface.get("segment")
#                 next_hop = iface.get("nextHop")
#                 if ip_mask=="0.0.0.0/0":
#                     print(next_hop)
#                 if next_hop!="null":
#                     config.append(f"ip route {ip_mask} {next_hop}")
#                     continue
#                 if next_hop=="null" and iface.get("interfaceName")!="null":
#                     config.append(f"ip route {ip_mask} {iface.get('interfaceName')}")
#                 else:
#                     config.append(f"ip route {ip_mask} NULL0")
#         # 策略定义
#         config.append("!! --- Policy Definitions Start ---")
#         # 默认 null policy
#         config.append("! null policy placeholder")
#         config.append("route-map null permit 10")
#         config.append("permit")
#         for route_policy in device.get("routePolicy", []):
#             p_id = route_policy.get("id")
#             config.append(policy_configs[str(p_id)])
#         config.append("!! --- Policy Definitions End ---\n")
#
#         # BGP 配置
#         config.append(f"router bgp {as_num}")
#         config.append(f" bgp router-id {router_id}")
#
#         # Network / redistribute / aggregate
#         for vrf in device.get("vrfMap", []):
#             networks = vrf.get("networkRouteList", {}).get("NetworkRouteDto", [])
#             for net in networks:
#                 seg = net.get('segment')
#                 policy_name = net.get('NetworkPolicyName')
#                 cmd = f"  network {seg}"
#                 if policy_name:
#                     cmd += f" route-map {policy_name} out"
#                 config.append(cmd)
#             # redistribute 等同于 IOS 命令
#             redistributes = vrf.get("importRouteList", {}).get("ImportRouteDto", [])
#             for red in redistributes:
#                 proto = red.get("protocol")
#                 policy = red.get("importPolicyName")
#                 cmd = f"  redistribute {proto}"
#                 if policy:
#                     cmd += f" route-map {policy} in"
#                 config.append(cmd)
#
#         # BGP 邻居
#         for edge in device.get("edgeList", []):
#             exp_id = edge.get("localExportPolicyId")
#             imp_id = edge.get("localImportPolicyId")
#
#             exp_policy = policy_data.get(exp_id, {}).get("name")
#             imp_policy = policy_data.get(imp_id, {}).get("name")
#
#             for peer_group in edge.get("peerList", []):
#                 remote_as = peer_group.get("remoteAsNumber")
#
#                 for remote_peer in peer_group.get("remotePeers", []):
#                     peer_ip = remote_peer.get("peerIp")
#                     config.append(f" neighbor {peer_ip} remote-as {remote_as}")
#                     if imp_policy:
#                         config.append(f"  route-map {imp_policy} in")
#                     if exp_policy:
#                         config.append(f"  route-map {exp_policy} out")
#         config.append("!")
#
#         ios_configs[hostname] = "\n".join(config)
#
#     return ios_configs

def generate_ios_xr_policies(policies_data):
    xr_configs_dict = {}

    for p_id, item in policies_data.items():
        p_name = item.get("name").replace(":", "_")  # IOS-XR policy 名称不能有空格
        nodes = item.get("policy", [])

        resource_definitions = []
        policy_body = [f"route-policy {p_name}"]
        policy_true = False
        for idx_node,node in enumerate(nodes):
            node_id = node.get("node")
            # IOS-XR 使用 if/else/endif 逻辑，类似于编程语言
            action_type = "pass" if node.get("rule") == "permit" else "drop"

            matches = node.get("matchConditions", [])
            actions = node.get("ApplyActionDto", {})

            # --- 1. 处理 Match 条件 ---
            match_clauses = []
            for idx, cond in enumerate(matches):
                m_type, m_val,m_mode = cond["type"], cond["policy"],cond["matchMode"]
                res_name = f"SET_{p_id}_{node_id}_{idx}"

                if m_type == "ip_prefix_filter":
                    m_val=m_val.replace("*", " ge ").replace("~", " le ")  # 去除空格，确保格式正确
                    resource_definitions.append(f"prefix-set {res_name}\n  {m_val}\nend-set")
                    match_clauses.append(f"destination in {res_name}")
                elif m_type == "as_path_filter":
                    # IOS-XR 正则需要放在 as-path-set 中
                    # resource_definitions.append(f"ip as-path access-list {res_name} {m_mode} '{m_val}'")
                    resource_definitions.append(f"as-path-set {res_name}\n  ios-regex '{m_val}'\nend-set")
                    match_clauses.append(f"as-path in {res_name}")
                elif m_type == "ip_community_filter":
                    # IOS-XR 社区也需要定义 set
                    m_val=str(m_val).replace(" ", ",")  # 将空格替换为逗号，适应 IOS-XR 的格式
                    resource_definitions.append(f"community-set {res_name}\n  {m_val}\nend-set")
                    match_clauses.append(f"community matches-every {res_name}")
            # 组合 Match 逻辑
            if idx_node == 0:
                # 第一个节点用 if
                if match_clauses:
                    policy_body.append(f"  if {' and '.join(match_clauses)} then")
                else:
                    policy_true=True
                    # policy_body.append("  if true then")
            else:
                if not policy_true:
                    # 后续节点用 elseif
                    if match_clauses:
                        policy_body.append(f"  elseif {' and '.join(match_clauses)} then")
                    else:
                        policy_body.append("  else")  # 没条件就用 else
            # if match_clauses:
            #     policy_body.append(f"  if {' and '.join(match_clauses)} then")
            # else:
            #     policy_body.append("  if true then")

            # --- 2. 处理 AS Path Attribute 操作 ---
            as_val = actions.get("asPath", "").strip()
            as_op = actions.get("asPathOperation")

            if as_op == "none":
                # 删除所有：匹配所有的 AS 并删除
                policy_body.append("    set as-path delete any ")

            # elif as_op == "delete":
            #     if as_val:
            #         # res_name = f"DEL_AS_{p_id}_{node_id}"
            #         # 匹配具体的 AS 数字
            #         # resource_definitions.append(f"as-path-set {res_name}\n  ios-regex '_{as_val}_'\nend-set")
            #         policy_body.append(f"    replace as-path \'{as_val}\'")
            #         # policy_body.append(f"   set as-path delete {res_name}")
            #
            # elif as_op == "overwrite":
            #     if as_val:
            #         # 模拟覆盖：匹配任意路径并替换为指定的 last-as 效果
            #         # 注意：IOS-XR 也可以直接 set as-path-prepend
            #         num_as = len(as_val.split())
            #         policy_body.append(f"    replace as-path \'{as_val} $asnum\'")

            elif as_op == "additive":
                if as_val:
                    num_as = as_val.split()
                    policy_body.append(f"    prepend as-path  {as_val[0]} {len(num_as)}")

            # --- 3. 其他属性 ---
            if actions.get("locPrf") != "null":
                policy_body.append(f"    set local-preference {actions['locPrf']}")

            # MED (Multi-Exit Discriminator)
            med_val = actions.get("med")
            if med_val and med_val != "null":
                policy_body.append(f"    set metric {med_val}")

            # Community 操作
            comm_val = actions.get("communityValue")
            if comm_val and comm_val != "null":
                # 清理并识别操作类型
                # 假设格式如 "65001:100 additive" 或 "no-export"
                clean_comm = comm_val.replace("no-export", "no-export").replace("no-advertise", "no-advertise")

                if "additive" in clean_comm:
                    # 追加模式：使用 set community (...) additive
                    val = clean_comm.replace("additive", "").strip()
                    # val = val.replace(" ", ",")  # 将空格替换为逗号，适应 IOS-XR 的格式
                    # policy_body.append(f"    set community ({val}) additive")
                    vals=val.split()  # 将空格替换为逗号，适应 IOS-XR 的格式
                    # IOS-XR 建议将多个 community 放在括号内
                    for v in vals:
                        policy_body.append(f"    set community {v} additive")

                elif clean_comm == "none" in clean_comm:
                    # 删除所有 community
                    policy_body.append(f"    set community none")

                else:

                    # 默认/重写模式：不带 additive 关键字即为覆盖
                    val = clean_comm.replace("overwrite", "").strip()
                    # val=val.replace(" ", ",")  # 将空格替换为逗号，适应 IOS-XR 的格式
                    # policy_body.append(f"    set community ({val})")
                    vals = val.split()  # 将空格替换为逗号，适应 IOS-XR 的格式
                    # IOS-XR 建议将多个 community 放在括号内

                    for index, v in enumerate(vals):
                        if index == 0:
                            policy_body.append(f"    set community {v}")
                        else:
                            policy_body.append(f"    set community {v} additive")
            # 结束当前节点的逻辑
            if not policy_true:
                policy_body.append(f"    {action_type}")
            # elif idx_node == 0:
            #     policy_body.append(f"    {action_type}")
        if not policy_true:
            policy_body.append("  endif")
        policy_body.append("end-policy")

        # 汇总配置 (去重资源定义)
        final_config = "!\n" + "\n".join(list(dict.fromkeys(resource_definitions))) + "\n!\n"
        final_config += "\n".join(policy_body) + "\n!"
        xr_configs_dict[str(p_id)] = final_config

    return xr_configs_dict


def generate_ios_xr_topology(network_data, policy_configs, policy_data):
    """
    输入多个设备的 network_data 列表，返回以 deviceName 为 key 的 Cisco IOS-XR 配置字典
    """
    xr_configs = {}
    dest=set()
    source=set()
    for device in network_data:
        hostname = device.get("deviceName")
        as_num = device.get("asNum")
        router_id = device.get("routerId", ["0.0.0.0"])[0]

        config = []
        config.append(f"!! --- Device: {hostname} ---")

        # 1. 基础系统配置
        config.append(f"hostname {hostname}")

        # 2. 接口配置 (interfaceList)
        # config.append("interface Loopback0")
        # config.append(f" ipv4 address {router_id}/32")
        for iface in device.get("interfaceList", []):
            name = iface.get("interfaceName",None)
            ip_mask = iface.get("segment")
            next_hop = iface.get("nextHop",None)
            type=iface.get("type")

            if ip_mask and ip_mask != "null" and type != "STATIC":
                # IOS-XR 接口配置不需要 unit 0，直接在接口下配置
                config.append(f"interface {name}")
                # 处理 IP/Mask (IOS-XR 通常使用 A.B.C.D/X 格式或 A.B.C.D E.F.G.H)
                config.append(f" ipv4 address {ip_mask}")
                config.append("!")
            if type == "STATIC":
                config.append(f"router static")
                config.append(f" address-family ipv4 unicast")
                ip_mask = iface.get("segment")
                next_hop = iface.get("nextHop")
                if next_hop != "null" and next_hop != "0.0.0.0":
                    config.append(f" {ip_mask} {next_hop}")
                elif  iface.get("interfaceName") != "null":
                    config.append(f" {ip_mask} {iface.get('interfaceName')}")
                else:
                    config.append(f" {ip_mask} NULL0")
                # 静态路由配置
                config.append(" !")
        # 3. 策略定义 (注入预先生成的 RPL 策略)
        config.append("!! --- Policy Definitions Start ---")
        # 假设 policy_configs 已经是 generate_ios_xr_policies 生成的字符串
        config.append("route-policy null \n  pass \nend-policy\n")
        for route_policy in device.get("routePolicy", []):
            p_id=route_policy.get("id")
            config.append(policy_configs[str(p_id)])

        config.append("!! --- Policy Definitions End ---\n")

        # 4. 路由实例与 BGP 基础配置
        # IOS-XR BGP 必须先定义全局 router-id
        config.append(f"router bgp {as_num}")
        config.append(f" bgp router-id {router_id}")
        config.append(" address-family ipv4 unicast")
        source.add(device.get("deviceName"))
        # --- A. 处理 Network 宣告 (静态路由 + BGP network) ---
        for vrf in device.get("vrfMap", []):
            networks = vrf.get("networkRouteList", {}).get("NetworkRouteDto", [])
            if networks:
                dest.add(device.get("deviceName"))

            for net in networks:
                seg = net.get('segment')
                policy_name = net.get('NetworkPolicyName').replace(':','_')
                config.append(f"  network {seg} ")
                # config.append(f"  network {seg} route-policy {policy_name}")
            redistributes= vrf.get("importRouteList", {}).get("ImportRouteDto", [])
            for red in redistributes:
                proto = red.get("protocol")
                policy = red.get("importPolicyName").replace(':','_')
                # 在 BGP 层级下配置 redistribute
                config.append(f"  redistribute {proto} route-policy {policy}")
            aggregates = vrf.get("aggregateRouteList", {}).get("AggregateRouteDto", [])
            for agg in aggregates:
                seg = agg.get("segment")
                attr_policy = agg.get("attributePolicyName").replace(':','_')
                supp_policy = agg.get("suppressPolicyName").replace(':','_')

                cmd = f"  aggregate-address {seg}"

                if attr_policy and attr_policy != "null":
                    cmd += f" route-policy {attr_policy}"

                # detailSuppress 为 true 时，通常对应 IOS-XR 的 summary-only
                if agg.get("detailSuppress") == "true":
                    config.append(f"{cmd} summary-only")
                else:
                    config.append(cmd)

                # 如果有具体的 suppress-policy (抑制明细路由)
                # if supp_policy and supp_policy != "null":
                #     config.append(f"suppress-policy {supp_policy}")
        config.append(" !")

        # 5. VRF 与 邻居配置
        for vrf in device.get("vrfMap", []):
            vrf_name = vrf.get("vrfName")
            is_public = vrf_name == "_public_"

            # IOS-XR VRF 逻辑
            if not is_public:
                config.append(f" vrf {vrf_name}")
                config.append(f"  address-family ipv4 unicast")
                config.append("  !")
                config.append(" !")

        # 6. BGP 邻居 (基于 edgeList)
        for edge in device.get("edgeList", []):
            exp_id = edge.get("localExportPolicyId")
            imp_id = edge.get("localImportPolicyId")

            exp_policy = policy_data.get(exp_id, {}).get("name").replace(":", "_")
            imp_policy = policy_data.get(imp_id, {}).get("name").replace(":", "_")

            for peer_group in edge.get("peerList", []):
                remote_as = peer_group.get("remoteAsNumber")

                for remote_peer in peer_group.get("remotePeers", []):
                    peer_ip = remote_peer.get("peerIp")
                    config.append(f" neighbor {peer_ip}")
                    config.append(f"  remote-as {remote_as}")
                    config.append(f"  address-family ipv4 unicast")
                    config.append(f"   send-community-ebgp")
                    # 应用出入口策略
                    if imp_policy:
                        config.append(f"   route-policy {imp_policy} in")
                    if exp_policy:
                        config.append(f"   route-policy {exp_policy} out")
                    config.append("  !")
                    config.append(" !")
        config.append("!")
        config.append("end")
        xr_configs[hostname] = "\n".join(config)

    return xr_configs,dest,source

def get_network(input_file):
    with open(input_file, "r") as f:
        network = {node['deviceIp']: node  for node in json.load(f)}
    return network

def get_policy(input_file):
    with open(input_file, "r") as f:
        policy = {po['id']: po   for po in json.load(f)}
    return policy

def write_topo_configs(topo_configs, output_dir):
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    for device_name, config in topo_configs.items():
        file_path = os.path.join(output_dir, f"{device_name}.cfg")
        with open(file_path, "w") as f:
            f.write(config)
def export_results(dest,source,result_dir,execution_time_all=0.0):
    result_file = os.path.join(result_dir, "config_result.json")
    data = {
        "dest": list(dest),
        "source": list(source),
        "TotalGenerationTime": round(execution_time_all, 2)
    }
    with open(result_file, 'w', encoding="utf-8") as f:
        json.dump(data, f, indent=4)

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
        description="Run Cisco Configuration Construction"
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
    type = args.type

    network_files = find_all_network_files(input_dir)

    for network_file in network_files:

        network_file = os.path.normpath(network_file)
        if "Fattree" in network_file:
            policy_file = "../benchmarks/Fattree/output/fattree_policy.json"
        else:
            if type == "bonsai":
                policy_file = re.sub(r"_bonsai.*", "_policy.json", network_file)
            else:
                policy_file = re.sub(r"_network.*", "_policy.json", network_file)

        path_parts = network_file.split(os.sep)
        # 至少保证有两级目录
        if len(path_parts) < 2:
            second_level_dir = path_parts[0]
        elif path_parts[2] == "Acorn":
            second_level_dir = path_parts[3]
        else:
            second_level_dir = path_parts[2]
        base_name = os.path.basename(network_file)
        name, ext = os.path.splitext(base_name)
        result_path = os.path.join(type,second_level_dir,name)
        output_dir = os.path.join(type,second_level_dir,name,"configs")
        os.makedirs(output_dir, exist_ok=True)
        network = get_network(network_file)
        polices = get_policy(policy_file)
        polices_config = generate_ios_xr_policies(polices)
        start_time=time.time()
        topo_configs,dest,source = generate_ios_xr_topology(list(network.values()), polices_config, polices)
        write_topo_configs(topo_configs, output_dir)
        end_time=time.time()
        execution_time_all=end_time-start_time
        export_results(dest, source,result_path,execution_time_all)