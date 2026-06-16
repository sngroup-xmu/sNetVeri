use crate::util::forward_action::ForwardAction;
use crate::util::network::Network;
use crate::util::rule::Rule;
use crate::verifier::bdd_engine::BddEngine;
use crate::verifier::context::Ctx;
use crate::verifier::device::Device;
use crate::verifier::lec::Lec;
use crate::verifier::node::Node;
use crate::verifier::rule_bdd::RuleBDD;
use biodivine_lib_bdd::*;
use rayon::prelude::*;
use std::collections::{HashMap, HashSet, VecDeque};
use std::hash::Hash;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Instant;
use crate::{EXIST_COUNT, NONEXIST_COUNT};
use crate::util::device_port::DevicePort;
#[derive(Hash, Eq, PartialEq, Clone)]
struct PortKey {
    ftype: String,
    port: String,
}
#[inline]
fn merge_bdd<K>(map: &mut HashMap<K, Bdd>, key: K, bdd: &Bdd)
where
    K: Eq + Hash,
{
    map.entry(key)
        .and_modify(|old| *old = old.or(bdd))
        .or_insert_with(|| bdd.clone());
}
#[derive(Clone)]
/// Toponet 验证核心：
/// 1) 将路由规则编码成 LEC(BDD)
/// 2) 将目的 packet space 编码成 BDD
/// 3) 在拓扑上做 BFS 风格的约束传播与计数
pub struct Toponet {
    ip_bits_len: usize,
    variables_dst_ip: Arc<Vec<BddVariable>>,
    variable_set: Arc<BddVariableSet>,
    map_device_lecs: Arc<HashMap<String, HashSet<Lec>>>,
    // map_device_phy_lecs: Arc<HashMap<String, HashMap<String, HashMap<String, Bdd>>>>,
    map_device_packet_space_bdd: Arc<HashMap<String, Bdd>>,
    map_device_phy_packet_space_bdd: Arc<HashMap<String, HashMap<String, Bdd>>>, //add
    prefix_bdd_cache: HashMap<usize, HashMap<String, Bdd>>, //add: prefix_len -> prefix -> bdd
    devices: Arc<HashMap<String, Device>>,
    network: Arc<Network>,
    dst_node_name: String,
    dst_phy_name: String,//add
    nodes_table: HashMap<String, Node>,
}

impl Toponet {
    /// 初始化 BDD 变量集合和运行时容器。
    pub fn new(ip_bits: usize) -> Self {
        let mut variable_builder = BddVariableSetBuilder::new();
        let mut variables_dst_ip = Vec::new();

        for i in 0..ip_bits {
            let var_name: String = format!("x{}", i + 1);
            let var = variable_builder.make_variable(&var_name);
            variables_dst_ip.push(var);
        }

        let variable_set = variable_builder.build();

        Toponet {
            ip_bits_len: ip_bits,
            variables_dst_ip: Arc::new(variables_dst_ip),
            variable_set: Arc::new(variable_set),
            map_device_lecs: Arc::new(HashMap::new()),
            // map_device_phy_lecs: Arc::new(HashMap::new()),
            map_device_packet_space_bdd: Arc::new(HashMap::new()),
            map_device_phy_packet_space_bdd: Arc::new(HashMap::new()),
            prefix_bdd_cache: HashMap::new(),
            devices: Arc::new(HashMap::new()),
            network: Arc::new(Network::new()),
            dst_node_name: String::new(),
            dst_phy_name: String::new(),//add
            nodes_table: HashMap::new(),
        }
    }

    pub fn get_variables_dst_ip(&self) -> &Vec<BddVariable> {
        &self.variables_dst_ip
    }
    pub fn get_variable_set(&self) -> &BddVariableSet {
        &self.variable_set
    }
    pub fn set_dst_node_name(&mut self, dst_node_name: String) {
        self.dst_node_name = dst_node_name
    }
    pub fn set_dst_phy_name(&mut self, dst_phy_name: String) {
        self.dst_phy_name = dst_phy_name;
    } //add

    pub fn get_dst_phys(&self, device_name: &str) -> Vec<String> {
        self.map_device_phy_packet_space_bdd
            .get(device_name)
            .map(|m| m.keys().cloned().collect())
            .unwrap_or_default()
    }//add

    pub fn set_arc_devices(&mut self, arc_devices: &Arc<HashMap<String, Device>>) {
        self.devices = Arc::clone(&arc_devices);
    }
    pub fn set_arc_network(&mut self, arc_network: &Arc<Network>) {
        self.network = Arc::clone(arc_network);
    }
    //add:收集所有前缀并编码
    pub fn build_prefix_bdd_cache(&mut self) {
        let mut unique: HashMap<usize, HashSet<String>> = HashMap::new();

        // 收集唯一前缀
        for device in self.devices.values().into_iter() {
            for rule in device.get_rules() {
                unique
                    .entry(rule.get_prefix_len())
                    .or_default()
                    .insert(rule.get_ip().to_string());
            }
            // for rule in device.get_packet_space() {
            //     unique
            //         .entry(rule.get_prefix_len())
            //         .or_default()
            //         .insert(rule.get_ip().to_string());
            // }
            // 按 phy packet space
            for rules in device.get_packet_space_by_physical().values() {
                for r in rules {
                    unique
                        .entry(r.get_prefix_len())
                        .or_default()
                        .insert(r.get_ip().to_string());
                }
            }
        }
        let items: Vec<(usize, String)> = unique
            .into_iter()
            .flat_map(|(plen, set)| set.into_iter().map(move |p| (plen, p)))
            .collect();

        let encoded: Vec<(usize, String, Bdd)> = items
            .par_iter()
            .map(|(plen, pfx)| {
                let b = BddEngine::encode_dst_ip_prefix(
                    pfx, *plen, self.get_variables_dst_ip(), self.get_variable_set(), self.ip_bits_len
                );
                (*plen, pfx.clone(), b)
            })
            .collect();

        // 编码唯一前缀
        let mut cache: HashMap<usize, HashMap<String, Bdd>> = HashMap::new();
        for (plen, ip, bdd) in encoded {
            cache.entry(plen).or_default().insert(ip, bdd);
        }
        self.prefix_bdd_cache = cache
            .into_iter()
            .map(|(k, v)| (k, v.into_iter().collect()))
            .collect();
        // for (plen, pfx, bdd) in encoded {
        //     cache.entry(plen).or_default().insert(pfx, bdd);
        // }
        // self.prefix_bdd_cache = cache;
    }

    #[inline]
    fn get_rule_bdd_cached(&self, ip: &str, plen: usize) -> Bdd {
        self.prefix_bdd_cache
            .get(&plen)
            .and_then(|m| m.get(ip))
            .expect("prefix_bdd_cache miss")
            .clone()
    }


    #[allow(dead_code)]
    /// 旧版 LEC 更新逻辑（当前主流程不依赖，保留用于对照）。
    fn update_device_lec(
        &self,
        device: &Device,
        match_bdd_map: &HashMap<Rule, RuleBDD>,
    ) -> HashSet<Lec> {
        let rules = device.get_rules();
        let mut port_predicate: HashMap<ForwardAction, Bdd> = HashMap::new();
        for rule in rules {
            let forward_action = &rule.get_forward_action();
            let tmp_ports = forward_action.get_ports();
            for port in tmp_ports {
                let cur_forward_action = ForwardAction::new(
                    forward_action.get_forward_type().clone(),
                    vec![port.clone()],
                    rule.get_physical_devices().clone(),
                );//每个port单独存
                if port_predicate.contains_key(&cur_forward_action) {
                    let old_predicate = port_predicate.get(&cur_forward_action).cloned().unwrap();
                    let new_hit = match_bdd_map.get(rule).unwrap().get_hit().clone();
                    let new_predicate = old_predicate.or(&new_hit);
                    port_predicate.insert(cur_forward_action.clone(), new_predicate);//按照port合并bdd
                } else {
                    port_predicate.insert(
                        cur_forward_action.clone(),
                        match_bdd_map.get(&rule).clone().unwrap().get_hit().clone(),
                    );
                }
            }
        }
        let mut tmp_lecs = HashSet::new();
        for (forward_action, predicate) in &port_predicate {
            tmp_lecs.insert(Lec::new(forward_action.clone(), predicate.clone()));
        }
        tmp_lecs
    }
    pub fn encode_rule_to_lec(&mut self, edge_devices: &HashSet<String>) {
        let tmp_map_device_lecs: HashMap<String, HashSet<Lec>> = self
            .devices
            .par_iter()
            .map(|(device_name, device)| {
                let is_edge = edge_devices.contains(device_name);
                // key 仍用 ForwardAction（其中包含 single-port + devices）
                let mut port_predicate: HashMap<ForwardAction, Bdd> = HashMap::new();
                // let mut rules = device.get_rules().clone();
                // rules.sort_by_key(|r| std::cmp::Reverse(r.get_prefix_len()));
                // 每个 devices 组各自维护“已覆盖空间”
                let mut covered_by_group: HashMap<Vec<String>, Bdd> = HashMap::new();

                // rules.sort_by_key(|r| std::cmp::Reverse(r.get_prefix_len()));
                let mut all_phys = device.get_physical_devices().clone();
                all_phys.sort();
                all_phys.dedup();
                for rule in device.get_rules() {

                    let bdd_match = self.get_rule_bdd_cached(rule.get_ip(), rule.get_prefix_len());

                    let fwd = rule.get_forward_action(); // 你当前实现是 clone 返回
                    let ftype = fwd.get_forward_type().clone();

                    // let devs = fwd.get_devices().clone();
                    // // 规范化 devices 组 key（避免同集合不同顺序被当成不同组）
                    // let mut devs_group = fwd.get_devices().clone();
                    // devs_group.sort();
                    // devs_group.dedup();
                    let mut devs_group = if is_edge {
                        fwd.get_devices().clone()
                    } else {
                        all_phys.clone()
                    };
                    devs_group.sort();
                    devs_group.dedup();

                    if devs_group.is_empty() {
                        continue;
                    }
                    let covered = covered_by_group
                        .entry(devs_group.clone())
                        .or_insert_with(|| self.variable_set.mk_false());
                    // 只针对同一 devices 组计算剩余 hit
                    let hit =bdd_match.and(&covered.not());
                    *covered = covered.or(&bdd_match);
                    if hit.is_false() {
                        continue;
                    }
                    for port in fwd.get_ports() {
                        let key = ForwardAction::new(
                            ftype.clone(),
                            vec![port.clone()], // 按单 port 拆
                            // devs.clone(),       // 保留该 rule 的 phy devices
                            devs_group.clone(),
                        );
                        port_predicate
                            .entry(key)
                            .and_modify(|old| *old = old.or(&hit))
                            .or_insert_with(|| hit.clone());
                    }
                }

                let mut lecs = HashSet::new();
                for (fa, pred) in port_predicate {
                    if !pred.is_false() {
                        lecs.insert(Lec::new(fa, pred));
                    }
                }

                (device_name.clone(), lecs)
            })
            .collect();

        self.map_device_lecs = Arc::new(tmp_map_device_lecs);
    }

    // pub fn encode_rule_to_lec(&mut self, edge_devices: &HashSet<String>) {
    //     let encoded: Vec<(
    //         String,                                              // device
    //         HashSet<Lec>,                                        // 全局 lecs
    //         HashMap<String, HashMap<String, Bdd>>,               // phy -> port -> bdd
    //     )> = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             let is_edge = edge_devices.contains(device_name);
    //
    //             // ---------- 1) 预建索引（每设备一次） ----------
    //             // port 索引
    //             let mut port_idx: HashMap<String, usize> = HashMap::new();
    //             let mut ports: Vec<String> = Vec::new();
    //
    //             // (forward_type, port) 索引（给全局 lecs 用）
    //             let mut act_port_idx: HashMap<String, HashMap<String, usize>> = HashMap::new();
    //             let mut act_port_keys: Vec<(String, String)> = Vec::new();
    //
    //             for rule in device.get_rules() {
    //                 let fwd = rule.get_forward_action();
    //                 let ftype = fwd.get_forward_type();
    //
    //                 for p in fwd.get_ports() {
    //                     // port 索引
    //                     if !port_idx.contains_key(p) {
    //                         let idx = ports.len();
    //                         port_idx.insert(p.clone(), idx);
    //                         ports.push(p.clone());
    //                     }
    //
    //                     // (action,port) 索引
    //                     let inner = act_port_idx.entry(ftype.clone()).or_default();
    //                     if !inner.contains_key(p) {
    //                         let idx = act_port_keys.len();
    //                         inner.insert(p.clone(), idx);
    //                         act_port_keys.push((ftype.clone(), p.clone()));
    //                     }
    //                 }
    //             }
    //
    //             // ---------- 2) 运行态聚合容器 ----------
    //             let mut covered_all = self.variable_set.mk_false();
    //             let mut global_pred: Vec<Bdd> = (0..act_port_keys.len())
    //                 .map(|_| self.variable_set.mk_false())
    //                 .collect();
    //
    //             // phy -> idx
    //             let phys = if is_edge { device.get_physical_devices().clone() } else { Vec::new() };
    //             let mut phy_idx: HashMap<&str, usize> = HashMap::with_capacity(phys.len());
    //             for (i, p) in phys.iter().enumerate() {
    //                 phy_idx.insert(p.as_str(), i);
    //             }
    //
    //             // phy 按 port 并集（不做 phy 余下 hit）
    //             let mut phy_port_pred: Vec<Vec<Bdd>> = if is_edge {
    //                 (0..phys.len())
    //                     .map(|_| (0..ports.len()).map(|_| self.variable_set.mk_false()).collect())
    //                     .collect()
    //             } else {
    //                 Vec::new()
    //             };
    //
    //             // ---------- 3) 主循环 ----------
    //             for rule in device.get_rules() {
    //                 let m = self.get_rule_bdd_cached(rule.get_ip(), rule.get_prefix_len());
    //                 let fwd = rule.get_forward_action();
    //                 let ftype = fwd.get_forward_type();
    //
    //                 // A) 全局仍做一次 LPM hit
    //                 let hit_all = m.and(&covered_all.not());
    //                 covered_all = covered_all.or(&m);
    //
    //                 if !hit_all.is_false() {
    //                     if let Some(inner) = act_port_idx.get(ftype) {
    //                         for p in fwd.get_ports() {
    //                             if let Some(&k) = inner.get(p) {
    //                                 let merged = global_pred[k].or(&hit_all);
    //                                 global_pred[k] = merged;
    //                             }
    //                         }
    //                     }
    //                 }
    //
    //                 // B) phy 侧：直接按 port 并集 m
    //                 if is_edge {
    //                     for phy in fwd.get_devices() {
    //                         let Some(&pi) = phy_idx.get(phy.as_str()) else { continue; };
    //                         let row = &mut phy_port_pred[pi];
    //
    //                         for p in fwd.get_ports() {
    //                             if let Some(&pj) = port_idx.get(p) {
    //                                 let merged = row[pj].or(&m);
    //                                 row[pj] = merged;
    //                             }
    //                         }
    //                     }
    //                 }
    //             }
    //
    //             // ---------- 4) 转回输出 ----------
    //             // 全局 lecs
    //             let mut lecs_all: HashSet<Lec> = HashSet::new();
    //             for (i, pred) in global_pred.into_iter().enumerate() {
    //                 if pred.is_false() {
    //                     continue;
    //                 }
    //                 let (ftype, port) = &act_port_keys[i];
    //                 let fa = ForwardAction::new(ftype.clone(), vec![port.clone()], Vec::new());
    //                 lecs_all.insert(Lec::new(fa, pred));
    //             }
    //
    //             // phy -> port -> bdd
    //             let mut phy_port_map: HashMap<String, HashMap<String, Bdd>> = HashMap::new();
    //             if is_edge {
    //                 for (pi, row) in phy_port_pred.into_iter().enumerate() {
    //                     let mut pm: HashMap<String, Bdd> = HashMap::new();
    //                     for (pj, b) in row.into_iter().enumerate() {
    //                         if !b.is_false() {
    //                             pm.insert(ports[pj].clone(), b);
    //                         }
    //                     }
    //                     if !pm.is_empty() {
    //                         phy_port_map.insert(phys[pi].clone(), pm);
    //                     }
    //                 }
    //             }
    //
    //             (device_name.clone(), lecs_all, phy_port_map)
    //         })
    //         .collect();
    //
    //     let mut all_map: HashMap<String, HashSet<Lec>> = HashMap::new();
    //     let mut phy_port_all: HashMap<String, HashMap<String, HashMap<String, Bdd>>> = HashMap::new();
    //
    //     for (name, lecs_all, phy_port) in encoded.into_iter() {
    //         all_map.insert(name.clone(), lecs_all);
    //         if !phy_port.is_empty() {
    //             phy_port_all.insert(name, phy_port);
    //         }
    //     }
    //
    //     self.map_device_lecs = Arc::new(all_map);
    //     self.map_device_phy_lecs = Arc::new(phy_port_all);
    // }
    // pub fn encode_rule_to_lec(&mut self, edge_devices: &HashSet<String>) {
    //     let encoded: Vec<(String, HashSet<Lec>, HashMap<String, HashMap<String, Bdd>>)> = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             let is_edge = edge_devices.contains(device_name);
    //
    //             // 全局聚合（所有节点都做）
    //             let mut covered_all = self.variable_set.mk_false();
    //             let mut port_pred_all: HashMap<PortKey, Bdd> = HashMap::new();
    //
    //             let mut phy_port_pred: HashMap<String, HashMap<String, Bdd>> = HashMap::new();
    //
    //             for rule in device.get_rules() {
    //                 let m = self.get_rule_bdd_cached(rule.get_ip(), rule.get_prefix_len());
    //
    //                 let fwd = rule.get_forward_action();
    //                 let ftype = fwd.get_forward_type().to_string();
    //
    //                 // A) 全局 hit
    //                 let hit_all = m.and(&covered_all.not());
    //                 covered_all = covered_all.or(&m);
    //
    //                 if !hit_all.is_false() {
    //                     for port in fwd.get_ports() {
    //                         let key = PortKey {
    //                             ftype: ftype.clone(),
    //                             port: port.clone(),
    //                         };
    //                         merge_bdd(&mut port_pred_all, key, &hit_all);
    //                     }
    //                 }
    //
    //                 // B) 仅 edge 节点做按 phy hit
    //                 if is_edge {
    //                     for phy in fwd.get_devices() {
    //                         let pm = phy_port_pred.entry(phy.clone()).or_default();
    //                         for port in fwd.get_ports() {
    //                             // 可选用 m；若想跟全局 LPM 一致可改为 hit_all
    //                             merge_bdd(pm, port.clone(), &m);
    //                         }
    //                     }
    //                 }
    //                 // if is_edge {
    //                 //     for phy in fwd.get_devices() {
    //                 //         let Some(&idx) = phy_idx.get(phy.as_str()) else {
    //                 //             continue;
    //                 //         };
    //                 //
    //                 //         let old_cov = covered_phy[idx].clone();
    //                 //         let hit_phy = m.and(&old_cov.not());
    //                 //         covered_phy[idx] = old_cov.or(&m);
    //                 //
    //                 //         if hit_phy.is_false() {
    //                 //             continue;
    //                 //         }
    //                 //
    //                 //         let pm = &mut phy_port_pred[idx];
    //                 //         for port in fwd.get_ports() {
    //                 //             pm.entry(port.clone())
    //                 //                 .and_modify(|old| *old = old.or(&hit_phy))
    //                 //                 .or_insert_with(|| hit_phy.clone());
    //                 //         }
    //                 //     }
    //                 // }
    //             }
    //
    //             // 转回全局 LEC
    //             let mut lecs_all: HashSet<Lec> = HashSet::new();
    //             for (k, pred) in port_pred_all {
    //                 let fa = ForwardAction::new(k.ftype, vec![k.port], Vec::new());
    //                 lecs_all.insert(Lec::new(fa, pred));
    //             }
    //
    //             // // 转回按 phy LEC（非 edge 为空）
    //             // let mut phy_port_map: HashMap<String, HashMap<String, Bdd>> = HashMap::new();
    //             // if is_edge {
    //             //     for (idx, pm) in phy_port_pred.into_iter().enumerate() {
    //             //         if !pm.is_empty() {
    //             //             phy_port_map.insert(phys[idx].clone(), pm);
    //             //         }
    //             //     }
    //             // }
    //
    //             (device_name.clone(), lecs_all, phy_port_pred)
    //         })
    //         .collect();
    //
    //     let mut all_map: HashMap<String, HashSet<Lec>> = HashMap::new();
    //     let mut phy_map: HashMap<String, HashMap<String, HashMap<String, Bdd>>> = HashMap::new();
    //
    //     for (name, lecs_all, lecs_phy) in encoded {
    //         all_map.insert(name.clone(), lecs_all);
    //         if !lecs_phy.is_empty() {
    //             phy_map.insert(name, lecs_phy);
    //         }
    //     }
    //
    //     self.map_device_lecs = Arc::new(all_map);
    //     self.map_device_phy_lecs = Arc::new(phy_map);
    // }
    //     let encoded: Vec<(String, HashSet<Lec>, HashMap<String, HashSet<Lec>>)> = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             // 全局（不分 phy）
    //             let mut covered_all = self.variable_set.mk_false();
    //             let mut port_pred_all: HashMap<ForwardAction, Bdd> = HashMap::new();
    //             let phys = device.get_physical_devices(); // &Vec<String>
    //             // 按 phy
    //             let mut covered_phy: HashMap<String, Bdd> = HashMap::new();
    //             let mut port_pred_phy: HashMap<String, HashMap<ForwardAction, Bdd>> = HashMap::new();
    //
    //             for phy in device.get_physical_devices() {
    //                 covered_phy.insert(phy.clone(), self.variable_set.mk_false());
    //                 port_pred_phy.insert(phy.clone(), HashMap::new());
    //             }
    //
    //             for rule in device.get_rules().into_iter() {
    //                 // 只编码一次
    //                 let m = self.get_rule_bdd_cached(rule.get_ip(), rule.get_prefix_len());
    //                 // let m = BddEngine::encode_dst_ip_prefix(
    //                 //     rule.get_ip(),
    //                 //     rule.get_prefix_len(),
    //                 //     self.get_variables_dst_ip(),
    //                 //     self.get_variable_set(),
    //                 //     self.ip_bits_len,
    //                 // );
    //
    //                 let fwd = rule.get_forward_action();
    //
    //                 // A) 全局 hit（不分 phy）
    //                 let hit_all = m.and(&covered_all.not());
    //                 covered_all = covered_all.or(&m);
    //
    //                 if !hit_all.is_false() {
    //                     for port in fwd.get_ports() {
    //                         let k = ForwardAction::new(
    //                             fwd.get_forward_type().clone(),
    //                             vec![port.clone()],
    //                             Vec::new(), // 全局分类不带设备维度
    //                         );
    //                         port_pred_all
    //                             .entry(k)
    //                             .and_modify(|old| *old = old.or(&hit_all))
    //                             .or_insert(hit_all.clone());
    //                     }
    //                 }
    //
    //                 // B) 按 phy hit（只对 rule 覆盖的 phy）
    //                 for phy in fwd.get_devices() {
    //                     let old_cov = covered_phy
    //                         .get(phy)
    //                         .cloned()
    //                         .unwrap_or_else(|| self.variable_set.mk_false());
    //                     let hit_phy = m.and(&old_cov.not());
    //                     let new_cov = old_cov.or(&m);
    //                     covered_phy.insert(phy.clone(), new_cov);
    //
    //                     if hit_phy.is_false() {
    //                         continue;
    //                     }
    //
    //                     let phy_map = port_pred_phy.entry(phy.clone()).or_default();
    //                     for port in fwd.get_ports() {
    //                         let k = ForwardAction::new(
    //                             fwd.get_forward_type().clone(),
    //                             vec![port.clone()],
    //                             vec![phy.clone()],
    //                         );
    //                         phy_map
    //                             .entry(k)
    //                             .and_modify(|old| *old = old.or(&hit_phy))
    //                             .or_insert(hit_phy.clone());
    //                     }
    //                 }
    //             }
    //
    //             // 转为 Lec 集合
    //             let mut lecs_all: HashSet<Lec> = HashSet::new();
    //             for (fa, pred) in port_pred_all {
    //                 lecs_all.insert(Lec::new(fa, pred));
    //             }
    //
    //             let mut lecs_phy: HashMap<String, HashSet<Lec>> = HashMap::new();
    //             for (phy, m) in port_pred_phy {
    //                 let mut s = HashSet::new();
    //                 for (fa, pred) in m {
    //                     s.insert(Lec::new(fa, pred));
    //                 }
    //                 lecs_phy.insert(phy, s);
    //             }
    //
    //             (device_name.clone(), lecs_all, lecs_phy)
    //         })
    //         .collect();
    //
    //     let mut all_map: HashMap<String, HashSet<Lec>> = HashMap::new();
    //     let mut phy_map: HashMap<String, HashMap<String, HashSet<Lec>>> = HashMap::new();
    //
    //     for (name, lecs_all, lecs_phy) in encoded {
    //         all_map.insert(name.clone(), lecs_all);
    //         phy_map.insert(name, lecs_phy);
    //     }
    //
    //     self.map_device_lecs = Arc::new(all_map);
    //     self.map_device_phy_lecs = Arc::new(phy_map);
    // }
    // /// 将每台设备的路由规则编码成“按单端口拆分”的 LEC 集合。
    // pub fn encode_rule_to_lec(&mut self) {
    //     let tmp_map_device_lecs = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             let mut port_predicate: HashMap<ForwardAction, Bdd> = HashMap::default();
    //             let tmp_rules = device.get_rules();
    //             let mut set_first = false;
    //             let mut all_bdd = self.variable_set.mk_true();
    //             for rule in tmp_rules.into_iter() {
    //                 let bdd_match: Bdd = BddEngine::encode_dst_ip_prefix(
    //                     rule.get_ip(),
    //                     rule.get_prefix_len(),
    //                     self.get_variables_dst_ip(),
    //                     self.get_variable_set(),
    //                     self.ip_bits_len,
    //                 );
    //                 let mut bdd_hit = bdd_match.clone();
    //                 if !set_first {
    //                     set_first = true;
    //                     all_bdd = bdd_match.clone();
    //                 } else {
    //                     // 按 LPM 顺序剔除已覆盖区域，得到该规则首次命中区域。
    //                     let tmp = all_bdd.not();
    //                     bdd_hit = bdd_match.and(&tmp);
    //                     all_bdd = all_bdd.or(&bdd_match);
    //                 }
    //                 let forward_action = &rule.get_forward_action();
    //                 let tmp_ports = forward_action.get_ports();
    //                 for port in tmp_ports {
    //                     let cur_forward_action = ForwardAction::new(
    //                         forward_action.get_forward_type().clone(),
    //                         vec![port.clone()],
    //                     );
    //                     if port_predicate.contains_key(&cur_forward_action) {
    //                         let old_predicate =
    //                             port_predicate.get(&cur_forward_action).cloned().unwrap();
    //                         let new_hit = bdd_hit.clone();
    //                         let new_predicate = old_predicate.or(&new_hit);
    //                         port_predicate.insert(cur_forward_action.clone(), new_predicate);
    //                     } else {
    //                         port_predicate.insert(cur_forward_action.clone(), bdd_hit.clone());
    //                     }
    //                 }
    //             }
    //             let mut tmp_lecs = HashSet::default();
    //             for (forward_action, predicate) in port_predicate.into_iter() {
    //                 tmp_lecs.insert(Lec::new(forward_action, predicate));
    //             }
    //             (device_name.clone(), tmp_lecs)
    //         })
    //         .collect();
    //     self.map_device_lecs = Arc::new(tmp_map_device_lecs);
    // }
    // ///add：编码packet space 为每个phy
    // pub fn encode_packet_space_by_physical(&mut self) {
    //     let tmp: HashMap<String, HashMap<String, Bdd>> = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             let mut phy_map: HashMap<String, Bdd> = HashMap::new();
    //
    //             for (phy, rules) in device.get_packet_space_by_physical() {
    //                 let mut acc = self.variable_set.mk_false();
    //
    //                 for rule in rules {
    //                     let bdd = BddEngine::encode_dst_ip_prefix(
    //                         rule.get_ip(),
    //                         rule.get_prefix_len(),
    //                         self.get_variables_dst_ip(),
    //                         self.get_variable_set(),
    //                         self.ip_bits_len,
    //                     );
    //                     acc = acc.or(&bdd);
    //                 }
    //
    //                 phy_map.insert(phy.clone(), acc);
    //             }
    //
    //             (device_name.clone(), phy_map)
    //         })
    //         .collect();
    //
    //     self.map_device_phy_packet_space_bdd = Arc::new(tmp);
    // }
    // /// 编码每台设备的 packet space（若存在）。
    // pub fn encode_packet_space(&mut self) {
    //     let start: Instant = Instant::now();
    //     let tmp_map_device_subnet_bdd: HashMap<String, Bdd> = self
    //         .devices
    //         .par_iter()
    //         .filter_map(|(device_name, device)| {
    //
    //             if let Some(packet_space) = device.get_packet_space() {
    //                 let bdd: Bdd = BddEngine::encode_dst_ip_prefix(
    //                     packet_space.get_ip(),
    //                     packet_space.get_prefix_len(),
    //                     self.get_variables_dst_ip(),
    //                     self.get_variable_set(),
    //                     self.ip_bits_len,
    //                 );
    //                 Some((device_name.clone(), bdd))
    //             } else {
    //                 None
    //             }
    //         })
    //         .collect();
    //     self.map_device_packet_space_bdd = Arc::new(tmp_map_device_subnet_bdd);
    //     let _duration: std::time::Duration = start.elapsed();
    // }
    pub fn encode_packet_space(&mut self) {
        let encoded: Vec<(String, HashMap<String, Bdd>)> = self
            .devices
            .par_iter()
            .map(|(device_name, device)| {
                // 全局 packet space
                // let global_bdd = device
                //     .get_packet_space()
                //     .as_ref()
                //     .map(|ps| self.get_rule_bdd_cached(ps.get_ip(), ps.get_prefix_len()));

                // 按 phy packet space
                let mut phy_map: HashMap<String, Bdd> = HashMap::new();
                for (phy, rules) in device.get_packet_space_by_physical() {
                    let mut acc = self.variable_set.mk_false();
                    for r in rules {
                        let b = self.get_rule_bdd_cached(r.get_ip(), r.get_prefix_len());
                        acc = acc.or(&b);
                    }
                    phy_map.insert(phy.clone(), acc);
                }

                (device_name.clone(), phy_map)
            })
            .collect();

        let mut global_map: HashMap<String, Bdd> = HashMap::new();
        let mut phy_all_map: HashMap<String, HashMap<String, Bdd>> = HashMap::new();

        for (name,  pm) in encoded {
            let mut acc = self.variable_set.mk_false();
            for b in pm.values() {
                acc = acc.or(&b);
            }
            global_map.insert(name.clone(), acc);
            phy_all_map.insert(name, pm);
        }

        self.map_device_packet_space_bdd = std::sync::Arc::new(global_map);
        self.map_device_phy_packet_space_bdd = std::sync::Arc::new(phy_all_map);
    }
    /// 获取当前目的节点的 packet space BDD。
    fn get_packet_space(&self) -> &Bdd {
        self.map_device_packet_space_bdd
            .get(&self.dst_node_name)
            .unwrap()
    }
    ///add：获取phy的packet space bdd
    pub fn get_packet_space_by_physical(
        &self,
    ) -> &Bdd {
        self.map_device_phy_packet_space_bdd
            .get(&self.dst_node_name).unwrap()
            .get(&self.dst_phy_name).unwrap()
    }
    /// 依据设备列表创建验证节点表。
    pub fn gen_topo_node(&mut self, devices_name: &Vec<String>) {
        for device_name in devices_name {
            let node = Node::new(device_name.clone());
            self.nodes_table.insert(device_name.clone(), node);
        }
    }

    /// 对每个非目的节点初始化 CIB 与 in-degree 计数。
    // pub fn node_cal_in_degree(&mut self) {
    //     let packet_space_bdd=self.get_packet_space_by_physical().clone();//add
    //
    //     for node in self.nodes_table.values_mut() {
    //         // if node.get_name() == self.dst_node_name {
    //         //     continue;
    //         // }
    //         // let packet_space_bdd = self
    //         //     .map_device_packet_space_bdd
    //         //     .get(&self.dst_node_name)
    //         //     .cloned()
    //         //     .unwrap();
    //         let device_lecs = self.map_device_lecs.get(&node.get_name()).unwrap();
    //         let device_phy_lecs=self.map_device_phy_lecs.get(&node.get_name()).unwrap();
    //         node.init_cib(
    //             self.network.as_ref(),
    //             packet_space_bdd.clone(),
    //             device_lecs,
    //             self.dst_node_name.clone(),
    //             device_phy_lecs
    //         );
    //     }
    // }

    pub fn prebuild_nodes_raw_templates(&mut self) {
        let empty_phy_lecs: HashMap<String,HashMap<String,Bdd>> = HashMap::new();

        for node in self.nodes_table.values_mut() {
            let name = node.get_name();
            let device_lecs = self.map_device_lecs.get(name).unwrap();
            // let device_phy_lecs = self
            //     .map_device_phy_lecs
            //     .get(name)
            //     .unwrap_or(&empty_phy_lecs);

            node.prebuild_raw_templates(
                self.network.as_ref(),
                device_lecs,
                // device_phy_lecs,
            );
        }
    }
    pub fn instantiate_nodes_for_current_dst_phy(&mut self) {
        let dst_packet_space_bdd = self.map_device_packet_space_bdd.get(&self.dst_node_name).unwrap();

        for node in self.nodes_table.values_mut() {
            node.instantiate_for_dst_phy(&dst_packet_space_bdd);
        }
    }

    /// 启动计数流程（当前仅封装 bfs）。
    pub fn start_count(&mut self, network: &Network, edge_devices: &HashSet<String>) {
        self.bfs(network, edge_devices);
    }

    /// 汇总边缘节点可达性结果并更新全局计数器。
    pub fn show_result(&mut self, edge_devices: &HashSet<String>) {
        // let dst_packet_space_bdd = self
        //     .map_device_packet_space_bdd
        //     .get(&self.dst_node_name)
        //     .unwrap();
        let dst_packet_space_bdd = self.get_packet_space_by_physical().clone();
        // let mut reach_cnt = 0;
        // let mut unreach_cnt = 0;
        // 本地聚合
        let mut local_exist: usize = 0;
        let mut local_nonexist: usize = 0;

        for node in self.nodes_table.values_mut() {
            let cur_node_name = node.get_name();
            if edge_devices.contains(cur_node_name)  {
                let device = self.devices.get(cur_node_name).unwrap();

                let pair=node.get_result(self.dst_node_name.clone(),self.dst_phy_name.clone(),&dst_packet_space_bdd,device.get_physical_devices());
                local_exist += pair.exist;
                local_nonexist += pair.nonexist;
               // if res == false {
                //     unreach_cnt += 1;
                // } else if res == true {
                //     reach_cnt += 1;
                // }
            }
        }
        EXIST_COUNT.fetch_add( local_exist, Ordering::SeqCst);
        NONEXIST_COUNT.fetch_add(local_nonexist, Ordering::SeqCst);

        // let _ = (reach_cnt, unreach_cnt);
    }
    fn build_active_indexes(
        &self,
        network: &Network,
    ) -> (
        HashSet<String>,              // active nodes
        HashMap<String, usize>,       // node -> idx
        HashMap<String, usize>,       // ingress port -> idx
    ) {
        let device_ports_topo = network.get_device_ports();
        let topology = network.get_toplogy();

        let active_nodes: HashSet<String> = self
            .nodes_table
            .iter()
            .filter_map(|(name, n)| if n.is_reachable() { Some(name.clone()) } else { None })
            .collect();

        let mut node_idx = HashMap::with_capacity(active_nodes.len());
        for (i, n) in active_nodes.iter().enumerate() {
            node_idx.insert(n.clone(), i);
        }

        // 只给 active 子图里会用到的 ingress port 编码
        let mut port_idx = HashMap::new();
        let mut next = 0usize;
        for src in &active_nodes {
            if let Some(ports) = device_ports_topo.get(src) {
                for p in ports {
                    if let Some(dst_port) = topology.get(p) {
                        let dst = dst_port.get_device_name();
                        if active_nodes.contains(dst) {
                            let ing = dst_port.get_port_name().to_string();
                            if !port_idx.contains_key(&ing) {
                                port_idx.insert(ing, next);
                                next += 1;
                            }
                        }
                    }
                }
            }
        }

        (active_nodes, node_idx, port_idx)
    }
    /// 在物理拓扑上执行通告传播：
    /// 从目的节点出发，沿端口反向/双向扩散，触发相邻节点约束收敛。
    pub fn bfs(&mut self, network: &Network, edge_devices: &HashSet<String>) {
        let device_ports_topo = network.get_device_ports();
        let topology = network.get_toplogy();

        let (active_nodes, name_to_idx, port_idx) = self.build_active_indexes(network);
        let start_context = Ctx::new(
            self.dst_node_name.clone(),
            self.get_packet_space().clone(),
            // self.get_packet_space_by_physical().clone(),
            1,
        );
        // let mut visited: HashSet<(String, String)> = HashSet::new();
        // let mut visited: HashSet<(usize, usize)> = HashSet::new();
        let mut visited: HashMap<(usize, usize, usize), Bdd> = HashMap::new();

        let mut queue: VecDeque<Ctx> = VecDeque::new();
        queue.push_back(start_context);//放入初始dst和packet
        let mut bfs_cnt = 0;//层数
        let mut ctx_cnt = 0;//生成的ctx数量
        let mut check_cnt = 0;//检查次数
        // let mut start=true;
        while !queue.is_empty() {//队列为空
            // bfs_cnt += 1;//bfs层数加1
            let size = queue.len();
            let mut indegree_check_set: HashSet<String> = HashSet::new();//入度节点
            for _ in 0..size {
                if let Some(current_ctx) = queue.pop_front() {
                    let cur_device_name = current_ctx.get_device_name().clone();//当前设备，从queue中取出的
                    // let mut _satisfied_count = 0;
                    if !active_nodes.contains(&cur_device_name) { continue; }
                    let Some(&src_idx) = name_to_idx.get(cur_device_name.as_str()) else { continue; };

                    if let Some(cur_ports) = device_ports_topo.get(&cur_device_name) {//获取当前设备所有端口
                        for cur_port in cur_ports {
                            if let Some(dst_port) = topology.get(cur_port) {
                                let dst_device_name = dst_port.get_device_name();//获得当前节点的邻居
                                if !active_nodes.contains(dst_device_name) { continue; }
                                let dst_node =
                                    self.nodes_table.get_mut(dst_device_name).unwrap();//获取邻居节点
                                // check_cnt += 1;//统计检查次数
                                let ingress = dst_port.get_port_name().clone();

                                let Some(&dst_i) = name_to_idx.get(dst_device_name) else { continue; };
                                let Some(&ing_i) = port_idx.get(&ingress) else { continue; };

                                // if dst_node.is_reachable(){
                                    if !dst_node.count_check(ingress.clone(), &current_ctx, edge_devices) {
                                        continue;
                                    }
                                    let Some(out) = dst_node.get_cib_out_by_port(&ingress) else { continue; };
                                    let out_pred = out.get_predicate().clone();

                                    let key = (src_idx, dst_i, ing_i);
                                    let delta = if let Some(seen) =visited.get(&key) {
                                        out_pred.and(&seen.not())
                                    } else {
                                        out_pred.clone()
                                    };

                                    if delta.is_false() {
                                        continue;
                                    }

                                    visited
                                        .entry(key)
                                        .and_modify(|old| *old = old.or(&out_pred))
                                        .or_insert(out_pred);
                                    queue.push_back(Ctx::new(dst_device_name.clone(), delta, 1));
                                    // let pair = (current_ctx.get_device_name().clone(), dst_device_name.clone());
                                    // let Some(&dst_idx) = name_to_idx.get(dst_device_name.as_str()) else { continue; };
                                    // if !visited.contains(&(src_idx, dst_idx)) {//防止循环
                                    //     indegree_check_set.insert(dst_device_name.clone());//记录入度
                                    //     let ingress_port = dst_port.get_port_name().clone();
                                    //     if dst_node.count_check(
                                    //         ingress_port.clone(),
                                    //         &current_ctx,
                                    //         edge_devices,
                                    //     ) {
                                    //         if let Some(out) = dst_node.get_cib_out_by_port(&ingress_port) {
                                    //             // ctx_cnt += 1;
                                    //             // _satisfied_count += 1;
                                    //             let new_ctx = Ctx::new(
                                    //                 dst_device_name.clone(),
                                    //                 out.get_predicate().clone(),
                                    //                 1,
                                    //             );
                                    //             queue.push_back(new_ctx);
                                    //         }
                                    //
                                    //         // ctx_cnt += 1;
                                    //         // _satisfied_count += 1;
                                    //         // let cib_out_announcement = dst_node.get_cib_out();
                                    //         // let new_ctx = Ctx::new(
                                    //         //     dst_device_name.clone(),
                                    //         //     cib_out_announcement.get_predicate().clone(),
                                    //         //     1,
                                    //         // );
                                    //         // queue.push_back(new_ctx);
                                    //     }
                                    //     if !edge_devices.contains(&ingress_port){
                                    //         visited.insert((src_idx, dst_idx));
                                    //     }
                                    //
                                    // }

                                // }
                            }
                        } 
                    }
                }
            }
            // for dst_device_name in indegree_check_set {
            //     let dst_node = self.nodes_table.get_mut(&dst_device_name).unwrap();
            //     let tmp_lec_cnt = dst_node.get_indegree_lec_cnt();
            //     if tmp_lec_cnt > 0 {}
            // }
        }
        let _ = (bfs_cnt, ctx_cnt, check_cnt);
    }

    // // 新增：算“next_node 从 ingress_port 入来的可接受谓词”
    // fn ingress_accept_predicate(&self, node_name: &str, ingress_port: &str) -> Bdd {
    //     let mut acc = self.variable_set.mk_false();
    //     if let Some(lecs) = self.map_device_lecs.get(node_name) {
    //         for lec in lecs {
    //             if lec
    //                 .forward_action
    //                 .get_ports()
    //                 .iter()
    //                 .any(|p| p == ingress_port)
    //             {
    //                 acc = acc.or(&lec.predicate);
    //             }
    //         }
    //     }
    //     acc
    // }

    // 新增：DFS 遍历，路径不记录；并集直接写入 nodes_table[node].union_predicate
    // pub fn dfs_fill_node_union(&mut self, network: &Network) {
    //     let device_ports_topo: &HashMap<String, HashSet<DevicePort>> = network.get_device_ports();//获取设备端口信息
    //     let topology: &HashMap<DevicePort, DevicePort> = network.get_toplogy();
    //
    //     // 每轮先清空 node 内并集
    //     for node in self.nodes_table.values_mut() {
    //         node.reset_union_predicate();
    //     }
    //
    //     let start_node = self.dst_node_name.clone();//当前起始node
    //     let start_bdd = self.get_packet_space_by_physical().clone();//对应phy 的packet space
    //
    //     // 栈元素：(当前节点, 当前可达谓词)
    //     let mut stack: Vec<(String, Bdd)> = vec![(start_node, start_bdd)];
    //
    //     while let Some((cur_node, cur_bdd)) = stack.pop() {
    //         if let Some(cur_ports) = device_ports_topo.get(&cur_node) {
    //             for cur_port in cur_ports {
    //                 if let Some(next_port) = topology.get(cur_port) {
    //                     let next_node_name = next_port.get_device_name();
    //                     let ingress_port_on_next = next_port.get_port_name();
    //
    //                     // 关键：在邻居上做 local_cib 交集并合并到邻居 union
    //                     let next_bdd_to_propagate: Option<Bdd> = {
    //                         let next_node = self.nodes_table.get_mut(&next_node_name).unwrap();
    //
    //                         if let Some(hit) = next_node
    //                             .intersect_with_local_cib_on_ingress(&ingress_port_on_next, &cur_bdd)
    //                         {
    //                             // union 真正变大才继续传播，避免环路空转
    //                             if next_node.merge_union_predicate(&hit) {
    //                                 Some(hit)
    //                             } else {
    //                                 None
    //                             }
    //                         } else {
    //                             None
    //                         }
    //                     };
    //
    //                     if let Some(next_bdd) = next_bdd_to_propagate {
    //                         stack.push((next_node_name, next_bdd));
    //                     }
    //                 }
    //             }
    //         }
    //     }
    // }
    // pub fn encode_rule_to_phy_lec(&mut self) {
    //     let tmp: HashMap<String, HashMap<String, HashSet<Lec>>> = self
    //         .devices
    //         .par_iter()
    //         .map(|(device_name, device)| {
    //             let mut phy_to_port_pred: HashMap<String, HashMap<ForwardAction, Bdd>> = HashMap::new();
    //             let mut phy_to_covered: HashMap<String, Bdd> = HashMap::new();
    //
    //             for phy in device.get_physical_devices() {
    //                 phy_to_port_pred.insert(phy.clone(), HashMap::new());
    //                 phy_to_covered.insert(phy.clone(), self.variable_set.mk_false());
    //             }
    //
    //             for rule in device.get_rules() {
    //                 let m = BddEngine::encode_dst_ip_prefix(
    //                     rule.get_ip(),
    //                     rule.get_prefix_len(),
    //                     self.get_variables_dst_ip(),
    //                     self.get_variable_set(),
    //                     self.ip_bits_len,
    //                 );
    //
    //                 for phy in device.get_physical_devices() {
    //                     if !rule.get_physical_devices().iter().any(|p| p == phy) {
    //                         continue;
    //                     }
    //
    //                     let covered = phy_to_covered.get(phy).unwrap().clone();
    //                     let hit = m.and(&covered.not());
    //                     if hit.is_false() {
    //                         let new_covered = covered.or(&m);
    //                         phy_to_covered.insert(phy.clone(), new_covered);
    //                         continue;
    //                     }
    //
    //                     let fwd = rule.get_forward_action();
    //                     for port in fwd.get_ports() {
    //                         let one_port_fwd = ForwardAction::new(
    //                             fwd.get_forward_type().clone(),
    //                             vec![port.clone()],
    //                         );
    //                         let port_map = phy_to_port_pred.get_mut(phy).unwrap();
    //                         if let Some(old) = port_map.get(&one_port_fwd) {
    //                             port_map.insert(one_port_fwd.clone(), old.or(&hit));
    //                         } else {
    //                             port_map.insert(one_port_fwd.clone(), hit.clone());
    //                         }
    //                     }
    //
    //                     let new_covered = covered.or(&m);
    //                     phy_to_covered.insert(phy.clone(), new_covered);
    //                 }
    //             }
    //
    //             let mut phy_lecs: HashMap<String, HashSet<Lec>> = HashMap::new();
    //             for (phy, port_pred) in phy_to_port_pred {
    //                 let mut set = HashSet::new();
    //                 for (fa, pred) in port_pred {
    //                     set.insert(Lec::new(fa, pred));
    //                 }
    //                 phy_lecs.insert(phy, set);
    //             }
    //
    //             (device_name.clone(), phy_lecs)
    //         })
    //         .collect();
    //
    //     self.map_device_phy_lecs = Arc::new(tmp);
    // }
    // /// 将某个 src-port device 的规则按 phy 维度编码成 BDD（带 LPM hit 语义）
    // fn encode_src_rule_bdd_by_phy(&self, src_device_name: &str) -> HashMap<String, Bdd> {
    //     let mut result: HashMap<String, Bdd> = HashMap::new();
    //     let Some(device) = self.devices.get(src_device_name) else {
    //         return result;
    //     };
    //
    //     // devices.json 里给这个逻辑设备绑定的所有 phy
    //     for phy in device.get_physical_devices() {
    //         let mut covered = self.variable_set.mk_false();
    //         let mut acc = self.variable_set.mk_false();
    //
    //         for rule in device.get_rules() {
    //             // 这条 rule 不属于当前 phy 就跳过
    //             let rule_phys = rule.get_physical_devices(); // Vec<String>
    //             if !rule_phys.iter().any(|p| p == phy) {
    //                 continue;
    //             }
    //
    //             let m = BddEngine::encode_dst_ip_prefix(
    //                 rule.get_ip(),
    //                 rule.get_prefix_len(),
    //                 self.get_variables_dst_ip(),
    //                 self.get_variable_set(),
    //                 self.ip_bits_len,
    //             );
    //
    //             // LPM: 只取当前还没被更高优先级前缀覆盖的部分
    //             let hit = m.and(&covered.not());
    //             if !hit.is_false() {
    //                 acc = acc.or(&hit);
    //             }
    //             covered = covered.or(&m);
    //         }
    //
    //         result.insert(phy.clone(), acc);
    //     }
    //
    //     result
    // }
    //对每个 src-port 找出具体可达的 phy device
    // pub fn show_result_src_phy_detail(&mut self, edge_devices: &HashSet<String>) {
    //     let dst_bdd = self.get_packet_space_by_physical().clone();
    //
    //     for src-port in edge_devices {
    //         let node_union_opt: Option<Bdd> = {
    //             match self.nodes_table.get_mut(src-port) {
    //                 None => None,
    //                 Some(node) => {
    //                     if node.get_local_cib().is_empty() {
    //                         let phy_cnt = self
    //                             .devices
    //                             .get(src-port)
    //                             .map(|d| d.get_physical_devices().len())
    //                             .unwrap_or(0);
    //                         NONEXIST_COUNT.fetch_add(phy_cnt, Ordering::SeqCst);
    //                         None
    //                     } else {
    //                         Some(node.get_cib_out().get_predicate().clone())
    //                     }
    //                 }
    //             }
    //         };
    //
    //         let Some(node_union) = node_union_opt else {
    //             continue;
    //         };
    //
    //         let phy_rule_bdd = self.encode_src_rule_bdd_by_phy(src-port);
    //         let mut reachable_phys: Vec<String> = Vec::new();
    //
    //         for (src_phy, src_rule_bdd) in phy_rule_bdd {
    //             let reachable_bdd = node_union.and(&src_rule_bdd).and(&dst_bdd);
    //             if !reachable_bdd.is_false() {
    //                 reachable_phys.push(src_phy);
    //                 EXIST_COUNT.fetch_add(1, Ordering::SeqCst);
    //             } else {
    //                 NONEXIST_COUNT.fetch_add(1, Ordering::SeqCst);
    //             }
    //         }
    //
    //         // reachable_phys.sort();
    //         // println!(
    //         //     "[phy-check-src-port] src_edge={} -> dst_edge={} dst_phy={} reachable_src_phys={:?}",
    //         //     src-port, self.dst_node_name, self.dst_phy_name, reachable_phys
    //         // );
    //     }
    // }
}
