use crate::util::network::Network;
use crate::verifier::annoucement::Announcement;
use crate::verifier::cibtuple::CibTuple;
use crate::verifier::context::Ctx;
use crate::verifier::lec::Lec;
use crate::{EXIST_COUNT, NONEXIST_COUNT};
use biodivine_lib_bdd::Bdd;
use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::io::empty;
use std::sync::atomic::Ordering;
use crate::util::forward_action::ForwardAction;


#[derive(Clone, Copy, Debug, Default)]
pub struct PairStats {
    pub exist: usize,
    pub nonexist: usize,
}

impl PairStats {
    #[inline]
    pub fn add_exist(&mut self, n: usize) { self.exist += n; }
    #[inline]
    pub fn add_nonexist(&mut self, n: usize) { self.nonexist += n; }

    #[inline]
    pub fn merge(&mut self, other: PairStats) {
        self.exist += other.exist;
        self.nonexist += other.nonexist;
    }
}
/// 验证阶段节点状态：
/// - local_cib: 当前节点每个入端口对应的聚合 CIB
/// - port_cib: 端口粒度的可继续切分 CIB 列表
///
#[derive(Clone)]
pub struct Node {
    name: String,
    in_degree_lec_cnt: i32,
    local_cib: HashMap<String, CibTuple>,
    reachable_phy:HashMap<String, Bdd>,
    // union_cib:HashMap<String, Bdd>,
    port_cib: HashMap<String, Vec<CibTuple>>,
    // src_phy_bdd_cache: HashMap<String, HashMap<String, Bdd>>,// add：该 node 按 phy 的规则并集BDD

    // 预构建模板（dst_phy 无关）
    raw_local_cib: HashMap<String, CibTuple>,
    raw_port_cib: HashMap<String, Vec<CibTuple>>,
    // raw_src_phy_bdd_cache: HashMap<String, HashMap<String, Bdd>>,
    raw_lec_predicates: Vec<Bdd>, // 用于快速重算 in_degree
    reachable:bool
}

impl Node {
    pub fn new(name: String) -> Self {
        Node {
            name,
            in_degree_lec_cnt: 0,
            local_cib: HashMap::new(),
            port_cib: HashMap::new(),
            reachable_phy: HashMap::new(),
            // union_cib: HashMap::new(),
            // src_phy_bdd_cache: HashMap::new(),
            raw_local_cib :HashMap::new(),
            raw_port_cib:HashMap::new(),
            raw_lec_predicates:Vec::new(),
            reachable:false
            // raw_src_phy_bdd_cache: HashMap::new(),
            // union_cib: HashMap::new(),//add
        }
    }


    pub fn get_name(&self) -> &String {
        &self.name
    }
    pub fn is_reachable(&self)->bool { self.reachable }
    pub fn get_indegree_lec_cnt(&self) -> i32 {
        self.in_degree_lec_cnt.clone()
    }

    pub fn prebuild_raw_templates(
        &mut self,
        network: &Network,
        lecs: &HashSet<Lec>,
        // phy_lecs: &HashMap<String, HashMap<String, Bdd>>,
    ) {
         self.raw_local_cib.clear();
        self.raw_port_cib.clear();
        // self.raw_src_phy_bdd_cache.clear();
        self.raw_lec_predicates.clear();

        // 端口桶结构（不依赖 dst_phy）
        if let Some(adj_ports) = network.get_device_ports().get(&self.name) {
            for adj_port in adj_ports {
                self.raw_port_cib
                    .insert(adj_port.get_port_name().to_string(), Vec::new());
            }
        }

        for lec in lecs {
            self.raw_lec_predicates.push(lec.predicate.clone());
            let raw_tuple=CibTuple::new(lec.predicate.clone(),lec.forward_action.clone(), 0);
            for port in lec.forward_action.get_ports() {
                if let Some(vec) = self.raw_port_cib.get_mut(port) {
                    vec.push(raw_tuple.clone());
                }
            }
        }
        // raw local/port（不做 packet_space 交集）
        for (port, cibs) in self.raw_port_cib.iter_mut() {
            let mut raw_tuple: Option<Bdd> = None;

            for cib in cibs.iter_mut() {
                raw_tuple = Some(match raw_tuple {
                    None => cib.get_predicate().clone(),
                    Some(acc) => acc.or(cib.get_predicate()),
                });  }
            if let Some(bdd_union) = raw_tuple {
                self.raw_local_cib.insert(
                    port.clone(),
                    CibTuple::new(bdd_union, cibs.first().unwrap().get_action().clone(), 0),
                );
            }
        }


        // // raw src-port-phy bdd（不做 dst_phy 裁剪）
        // for (phy, phy_lec_set) in phy_lecs {
        //     let mut port_map: HashMap<String, Bdd> = HashMap::new();
        //
        //     for (port,lec) in phy_lec_set {
        //         // for port in lec.forward_action.get_ports() {
        //             port_map
        //                 .entry(port.clone())
        //                 .and_modify(|old| *old = old.or(&lec))
        //                 .or_insert_with(|| lec.clone());
        //         // }
        //     }
        //
        //     if !port_map.is_empty() {
        //         self.raw_src_phy_bdd_cache.insert(phy.clone(), port_map);
        //     }
        // }
    }
    /// 增量构建：每个 dst_phy 调一次（只做 raw ∩ dst_packet_space）
    pub fn instantiate_for_dst_phy(&mut self, dst_packet_space_bdd: &Bdd) {
        self.local_cib.clear();
        self.reachable_phy.clear();
        // self.union_cib.clear();
        self.port_cib.clear();
        // self.src_phy_bdd_cache.clear();
        self.in_degree_lec_cnt = 0;
        self.reachable = false;
        // 1) port_cib 过滤
        for (port, raw_vec) in &self.raw_port_cib {
            let mut v: Vec<CibTuple> = Vec::new();
            for raw in raw_vec {
                let cut = raw.get_predicate().and(dst_packet_space_bdd);
                if cut.is_false() {
                    continue;
                }
                let mut t = raw.clone();
                t.set_predicate(cut);
                t.set_count(0);
                v.push(t);
                self.reachable = true;
            }
            self.port_cib.insert(port.clone(), v);
        }

        // 2) local_cib 从过滤后的 port_cib 建立，避免 raw_local 覆盖导致端口缺失。
        // let empty = dst_packet_space_bdd.and_not(dst_packet_space_bdd);
        for (port, vec) in &self.raw_local_cib {
            let  intersection=vec.clone().get_predicate().and(dst_packet_space_bdd);
            // if let Some(last) = vec.last() {
            //     let mut t = last.clone();
            //     t.set_count(0);
            if!intersection.is_false(){
                self.local_cib.insert(port.clone(),  CibTuple::new(intersection, vec.get_action().clone(), 0),
                );
            }

                // self.union_cib.insert(port.clone(), empty.clone());
            // }
        }

        // 3) src_phy_bdd_cache 过滤
        // raw_src_phy_port_bdd_cache 需要你在 prebuild 阶段先准备好（见下一段）
        // for (phy, port_map_raw) in &self.raw_src_phy_bdd_cache {
        //     let mut port_map_cut: HashMap<String, Bdd> = HashMap::new();
        //
        //     for (port, raw_bdd) in port_map_raw {
        //         let cut = raw_bdd.and(dst_packet_space_bdd);
        //         if !cut.is_false() {
        //             port_map_cut.insert(port.clone(), cut);
        //         }
        //     }
        //
        //     if !port_map_cut.is_empty() {
        //         self.src_phy_bdd_cache.insert(phy.clone(), port_map_cut);
        //     }
        // }

        // 4) in_degree 快速重算（按 raw lec 是否与 dst_phy 有交集）
        self.in_degree_lec_cnt = self
            .raw_lec_predicates
            .iter()
            .filter(|p| !p.and(dst_packet_space_bdd).is_false())
            .count() as i32;
    }
    // pub fn init_cib(
    //     &mut self,
    //     network: &Network,
    //     packet_space_bdd: Bdd,
    //     lecs: &HashSet<Lec>,
    //     _dst_node_name: String,
    //     phy_lecs: &HashMap<String, HashSet<Lec>>,
    // ) {
    //     // 为每个相邻端口初始化空 CIB 桶。
    //     if let Some(adj_ports) = network.get_device_ports().get(&self.name.clone()) {
    //         // let empty_bdd = packet_space_bdd.clone().and_not(&packet_space_bdd);
    //         for adj_port in adj_ports {
    //             self.port_cib
    //                 .insert(adj_port.get_port_name().to_string(), Vec::new());
    //             // self.union_cib.insert(adj_port.get_port_name().to_string(), empty_bdd.clone());
    //         }
    //     }
    //     for lec in lecs.clone() {
    //         // 仅保留落在目的 packet space 内的谓词。
    //         let intersection_bdd = packet_space_bdd.and(&lec.predicate);
    //         if intersection_bdd.is_false() {
    //             continue;
    //         } else {
    //             let new_cibtuple =
    //                 CibTuple::new(intersection_bdd.clone(), lec.forward_action.clone(), 0);
    //             for port in lec.forward_action.get_ports() {
    //                 if let Some(vec) = self.port_cib.get_mut(port) {
    //                     vec.push(new_cibtuple.clone());
    //                 }
    //                 self.local_cib.insert(port.clone(), new_cibtuple.clone());
    //             }
    //             self.in_degree_lec_cnt += 1;
    //         }
    //     }
    //     for (phy, lecs) in phy_lecs {
    //         let mut acc = packet_space_bdd.and_not(&packet_space_bdd);
    //         for lec in lecs {
    //             let p = lec.predicate.and(&packet_space_bdd);
    //             acc = acc.or(&p);
    //         }
    //         self.src_phy_bdd_cache.insert(phy.clone(), acc);
    //     }
    // }
    pub fn update_loc_cib(&mut self, from_port_name: String, annoucement: Announcement) -> bool {
        // 取出该端口当前待处理列表，避免可变借用冲突
        let old_vec = {
            let Some(cibtuples) = self.port_cib.get_mut(&from_port_name) else {
                return false;
            };
            std::mem::take(cibtuples)
        };

        if old_vec.is_empty() {
            return false;
        }

        let ann_pred = annoucement.get_predicate();

        let mut new_vec: Vec<CibTuple> = Vec::with_capacity(old_vec.len());
        let mut any_hit = false;
        let mut hit_union: Option<Bdd> = None;
        let mut first_hit_action = None::<crate::util::forward_action::ForwardAction>;

        for mut cibtuple in old_vec {
            let old_pred = cibtuple.get_predicate().clone();
            let inter = ann_pred.and(&old_pred);

            // 没命中：原样保留
            if inter.is_false() {
                new_vec.push(cibtuple);
                continue;
            }

            // 命中：记录 reachable phy
            any_hit = true;
            for phy in cibtuple.get_action().get_devices() {
                // self.reachable_phy.insert(phy.clone());
                self.reachable_phy
                    .entry(phy.clone())
                    .and_modify(|old| *old = old.or(&inter))
                    .or_insert_with(|| inter.clone());

            }

            // 累积本端口本次命中并集（用于更新 local_cib）
            hit_union = Some(match hit_union {
                None => inter.clone(),
                Some(acc) => acc.or(&inter),
            });
            if first_hit_action.is_none() {
                first_hit_action = Some(cibtuple.get_action().clone());
            }

            // 全命中：该分片消费掉；部分命中：把剩余放回队列
            if inter == old_pred {
                self.in_degree_lec_cnt -= 1;
            } else {
                let remain = cibtuple.keep_and_split(inter, 1);
                if !remain.get_predicate().is_false() {
                    new_vec.push(remain);
                }
            }
        }

        // 写回端口剩余待处理队列
        self.port_cib.insert(from_port_name.clone(), new_vec);

        // 更新 local_cib 的该端口快照
        if let Some(pred) = hit_union {
            let action = first_hit_action.expect("hit_union exists but action missing");
            self.local_cib
                .insert(from_port_name, CibTuple::new(pred, action, 1));
            true
        } else {
            // 本次该端口没有任何命中，清掉旧快照，避免脏状态
            self.local_cib.remove(&from_port_name);
            false
        }
    }
    /// 收到通告后更新某入端口 CIB：
    /// true 表示该端口约束已“收敛”，false 表示仍需等待更多信息。
    // pub fn update_loc_cib(&mut self, from_port_name: String, annoucement: Announcement) -> bool {
    //     let cibtuples = self.port_cib.get_mut(&from_port_name).unwrap(); //获取端口对应cib
    //     if let Some(mut cibtuple) = cibtuples.pop() {
    //         let intersection_bdd = annoucement.get_predicate().and(cibtuple.get_predicate());
    //         if !intersection_bdd.is_false() {
    //             for phy in cibtuple.get_action().get_devices() {
    //                 self.reachable_phy.insert(phy.clone());
    //             }
    //         }
    //         if intersection_bdd != cibtuple.get_predicate().clone() { //交集变小了
    //             if let Some(old_cibtuple) = self.local_cib.get_mut(&from_port_name) {
    //                 old_cibtuple.set_predicate(intersection_bdd.clone());
    //                 old_cibtuple.set_count(1);
    //             } else {
    //                 let mut t = cibtuple.clone();
    //                 t.set_predicate(intersection_bdd.clone());
    //                 t.set_count(1);
    //                 self.local_cib.insert(from_port_name.clone(), t);
    //             }
    //             // 切分后把“未命中部分”放回端口桶，等待后续处理。
    //             let new_cibtuple = cibtuple.keep_and_split(intersection_bdd.clone(), 1);
    //             cibtuples.push(new_cibtuple);
    //             return true
    //         } else {
    //             if let Some(old_cibtuple) = self.local_cib.get_mut(&from_port_name) {
    //                 old_cibtuple.set_predicate(intersection_bdd.clone());
    //                 old_cibtuple.set_count(1);
    //             } else {
    //                 let mut t = cibtuple.clone();
    //                 t.set_predicate(intersection_bdd.clone());
    //                 t.set_count(1);
    //                 self.local_cib.insert(from_port_name.clone(), t);
    //             }
    //             self.in_degree_lec_cnt -= 1;
    //             return true;
    //         }
    //     }
    //     true
    // }

    /// 判定当前节点是否可以向外继续传播上下文。
    pub fn count_check(
        &mut self,
        port_name: String,
        current_ctx: &Ctx,
        edge_devices: &HashSet<String>,
    ) -> bool {
        let annoucement = current_ctx.get_announcement();
        // if self.local_cib.get(port_name).unwrap().get_predicate()is_empty() { //本身已经是空
        //     return false;
        // }
        if !self.update_loc_cib(port_name.clone(), annoucement.clone()) { //交完了的是空
            return false;
        }
        if edge_devices.contains(self.get_name()) { //匹配到目标device
            return false;
        }
        // if self.in_degree_lec_cnt != 0 { //
        //     return false;
        // }
        true
    }

    pub fn get_cib_out_by_port(&self, port_name: &str) -> Option<Announcement> {
        let cib = self.local_cib.get(port_name)?;
        Some(Announcement::new(
            cib.get_predicate().clone(),
            cib.get_count(),
        ))
    }
    /// 汇总 local_cib 得到该节点输出通告。
    pub fn get_cib_out(&mut self) -> Announcement {
        let mut has_result = false;
        let mut count_predicate: HashMap<i32, Bdd> = HashMap::new();
        for cibtuple in self.local_cib.values() {
            let tmp_count = cibtuple.get_count();
            if tmp_count == 1 {
                has_result = true;
            }
            let tmp_bdd = cibtuple.get_predicate();
            if !count_predicate.contains_key(&tmp_count) {
                count_predicate.insert(tmp_count, tmp_bdd.clone());
            } else {
                let new_bdd = count_predicate.get(&tmp_count).unwrap().clone().or(tmp_bdd);
                count_predicate.insert(tmp_count, new_bdd);
            }
        }
        if !has_result {
            let annoucement_out: Announcement =
                Announcement::new(count_predicate.get(&0).unwrap().clone(), 0);
            annoucement_out
        } else {
            let annoucement_out = Announcement::new(count_predicate.get(&1).unwrap().clone(), 1);
            annoucement_out
        }
    }

    pub fn get_result(
        &mut self,
        _dst_name: String,
        _dst_phy_name: String,
        _dst_packet_space_bdd: &Bdd,
        src_all_phys: &[String],
    ) -> PairStats {
        let mut s = PairStats::default();

        // // 只统计当前 src_edge 的真实 phy 集合里有多少已被标记可达
        // let exist = src_all_phys
        //     .iter()
        //     .filter(|phy| self.reachable_phy.contains(*phy))
        //     .count();
        // 
        // let total = src_all_phys.len();
        // s.add_exist(exist);
        // s.add_nonexist(total.saturating_sub(exist));
        // s

        let mut exist = 0usize;

        for phy in src_all_phys {
            if let Some(pred) = self.reachable_phy.get(phy) {
                if !pred.and(_dst_packet_space_bdd).is_false() {
                    exist += 1;
                }
            }
        }

        s.add_exist(exist);
        s.add_nonexist(src_all_phys.len().saturating_sub(exist));
        s
    }
    // /// 在最终统计阶段判断当前边缘节点是否可达目的 packet space。
    // pub fn get_result(&mut self, dst_name: String, dst_phy_name: String, dst_packet_space_bdd: &Bdd, src_all_phys: &[String],) -> PairStats {
    //     let mut s = PairStats::default();
    //     // let mut union_all_opt: Option<Bdd> = None;
    //     // for b in self.union_cib.values() {
    //     //     union_all_opt = Some(match union_all_opt {
    //     //         None => b.clone(),
    //     //         Some(acc) => acc.or(b),
    //     //     });
    //     // }
    //     // let union_all = match union_all_opt {
    //     //     None => {
    //     //         s.add_nonexist(self.src_phy_bdd_cache.len());
    //     //             // println!(
    //     //             //     "[phy-check-src-port] dst_edge={}, dst_phy={}:src_edge={}  reachable_src_phys={:?}",
    //     //             //     dst_name,dst_phy_name,self.name,false
    //     //             // );
    //     //         return s;
    //     //     }
    //     //     Some(b) => b,
    //     // };
    //     // if self.local_cib.len() == 0 {
    //     //     s.add_nonexist(self.src_phy_bdd_cache.len());
    //     //     // NONEXIST_COUNT.fetch_add(self.src_phy_bdd_cache.len(), Ordering::SeqCst);
    //     //     // println!(
    //     //     //     "[phy-check-src-port] dst_edge={}, dst_phy={}:src_edge={}  reachable_src_phys={:?}",
    //     //     //     dst_name,dst_phy_name,self.name,false
    //     //     // );
    //     // } else {
    //
    //     // let annoucement_final = self.get_cib_out(); //最终传播结果
    //     // let packet_space_bdd_final = annoucement_final.get_predicate();
    //     // let count_final = annoucement_final.get_count();
    //
    //     // let contains_dst = union_all.and(dst_packet_space_bdd).is_false();
    //     // if !contains_dst {
    //     let mut reachable_phys: HashSet<String> = HashSet::new();
    //     let mut left_phy:HashSet<String> = HashSet::new();
    //     let total_phy = src_all_phys.len();
    //     for phy in src_all_phys {
    //         left_phy.insert(phy.clone());
    //     }
    //
    //     for (port, cibs) in &self.raw_port_cib {
    //         let Some(union_port_bdd) = self.union_cib.get(port) else {
    //             continue;
    //         };
    //
    //         for cib in cibs {
    //             let fws = cib.get_action();
    //             let phys = fws.get_devices();
    //             let mut ifcheck=false;
    //             for phy in phys {
    //                 if left_phy.contains(phy)
    //                 {
    //                     ifcheck=true;
    //                 }
    //             }
    //            if ifcheck {
    //                let check = union_port_bdd.and(cib.get_predicate()).and(dst_packet_space_bdd).is_false();
    //                if !check {
    //                    for phy in phys {
    //                        reachable_phys.insert(phy.clone());
    //                        left_phy.remove(phy);
    //                    }
    //                }
    //            }
    //
    //         }
    //         if reachable_phys.len() == total_phy {
    //             break;
    //         }
    //     }
    //     s.add_exist(reachable_phys.len());
    //     if total_phy-reachable_phys.len()>0{
    //         s.add_nonexist(total_phy-reachable_phys.len())
    //     }
    //     // let mut ok = false;
    //     // for (port, src_phy_port_bdd) in port_map {
    //     //     let Some(union_port_bdd) = self.union_cib.get(port) else {
    //     //         continue;
    //     //     };
    //     //
    //     //     let check = union_port_bdd
    //     //         .and(src_phy_port_bdd)
    //     //         .and(dst_packet_space_bdd);
    //     //
    //     //     if !check.is_false() {
    //     //         ok = true;
    //     //         break;
    //     //     }
    //     // }
    //     //
    //     // if ok { s.add_exist(1); } else { s.add_nonexist(1); }
    //     // // let check = union_all.and(dst_packet_space_bdd).and(lecs);
    //     // if !check.is_false() {
    //     //     s.add_exist(1);
    //     //     // EXIST_COUNT.fetch_add(1, Ordering::SeqCst);
    //     //     // println!(
    //     //     //     "[phy-check-src-port] dst_edge={}, dst_phy={}:src_edge={} -> src_phy={} reachable_src_phys={:?}",
    //     //     //      dst_name,dst_phy_name,self.name, phy,true
    //     //     // );
    //     // } else {
    //     //     s.add_nonexist(1);
    //     //     // NONEXIST_COUNT.fetch_add(1, Ordering::SeqCst);
    //     //     // println!(
    //     //     //     "[phy-check-src-port] dst_edge={}, dst_phy={}:src_edge={} -> src_phy={} reachable_src_phys={:?}",
    //     //     //     dst_name,dst_phy_name,self.name, phy,false
    //     //     // );
    //     // }
    //     // }
    //     // } else {
    //     //     s.add_nonexist(self.src_phy_bdd_cache.len());
    //     //     // NONEXIST_COUNT.fetch_add(self.src_phy_bdd_cache.len(), Ordering::SeqCst);
    //     //     //     println!(
    //     //     //         "[phy-check-src-port] dst_edge={}, dst_phy={}:src_edge={}  reachable_src_phys={:?}",
    //     //     //         dst_name,dst_phy_name,self.name,false
    //     //     //     );
    //     // }
    //     // }
    //     s
    // }
}
impl Hash for Node {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.name.hash(state);
    }
}

impl PartialEq for Node {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name
    }
}

impl Eq for Node {}
