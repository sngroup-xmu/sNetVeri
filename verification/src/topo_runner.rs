use crate::util::network::Network;
use crate::util::rule::Rule;
use crate::verifier::device::Device;
use crate::verifier::toponet::Toponet;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{BufRead, BufReader};
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Instant;

#[derive(Debug, Clone, Serialize, Deserialize, Hash, PartialEq, Eq)]
/// 子网定义：前缀地址 + 前缀长度。
pub struct SubNet {
    pub prefix: IpAddr,
    pub prefix_len: u8,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
/// packet_space 中间表示：目标前缀属于哪个宿主设备。
pub struct Packet {
    prefix: String,
    prefix_len: usize,
    host_name: String,
    device_name: String,//add
}

/// 顶层运行器：负责“读入 -> 编码 -> 验证”的完整流程编排。
pub struct TopoRunner {
    file_dir: String,
    devices_name: Vec<String>,
    devices: Arc<HashMap<String, Device>>,
    edge_devices: HashSet<String>,
    src_toponet: Toponet,
    network: Arc<Network>,
}

impl TopoRunner {
    /// 创建运行器，初始化核心容器。
    pub fn new(ip_bits: usize) -> Self {
        let num_cpu = num_cpus::get();
        println!("Number of logical cores: {}", num_cpu);
        TopoRunner {
            file_dir: String::new(),
            devices_name: Vec::new(),
            devices: Arc::new(HashMap::new()),
            edge_devices: HashSet::new(),
            src_toponet: Toponet::new(ip_bits),
            network: Arc::new(Network::new()),
        }
    }

    pub fn set_file_dir(&mut self, file_dir: &str) {
        self.file_dir = file_dir.to_string();
    }

    /// 扫描 routes 目录，收集设备名（文件名即设备名）。
    pub fn get_devices_name(&mut self) {
        let routes_dir: String = format!("{}/routes", self.file_dir);
        let entries = fs::read_dir(&routes_dir).expect("Failed to read directory");

        for entry in entries {
            let entry = entry.expect("Failed to read directory entry");
            let file_name = entry.file_name();
            let device_name = file_name.to_string_lossy().to_string();
            self.devices_name.push(device_name.clone());
        }
    }

    pub fn convert_subnet_devices_to_packets(//旧格式
        subnet_devices: HashMap<String, Vec<SubNet>>,
    ) -> Vec<Packet> {
        // 兼容 packet_space.json 的对象格式：
        // { "hostA": [subnet...], "hostB": [subnet...] } -> Vec<Packet>
        let mut packets = Vec::new();
        for (host_name, subnets) in subnet_devices {
            for subnet in subnets {
                let packet = Packet {
                    prefix: subnet.prefix.to_string(),
                    prefix_len: subnet.prefix_len as usize,
                    host_name: host_name.clone(),
                    device_name:host_name.clone(),
                };
                packets.push(packet);
            }
        }
        packets
    }

    pub fn read_devices_files(&mut self) {
        // 并行读取每台设备的路由文件。
        let mut tmp_devices: HashMap<String, Device> = self
            .devices_name
            .par_iter()
            .map(|device_name| {
                let mut tdevice: Device = Device::new(device_name.clone());
                let rule_file_path = format!("{}/routes/{}", self.file_dir, device_name);
                tdevice.read_rules_file(&rule_file_path);
                (device_name.clone(), tdevice)
            })
            .collect();

        let packet_space_file_path = format!("{}/results/packet_space.json", self.file_dir);
        let contents =
            fs::read_to_string(packet_space_file_path).expect("Error while reading the file");
        let packet_space_input: Value =
            serde_json::from_str(&contents).expect("Error while parsing the JSON");

        let packets = match packet_space_input {
            Value::Array(array) => {
                // 新格式：直接是 Packet 数组。
                serde_json::from_value(Value::Array(array)).expect("error parsing array")
            }
            Value::Object(map) => {
                // 旧格式：host -> subnet 列表。
                let subnet_devices: HashMap<String, Vec<SubNet>> =
                    serde_json::from_value(Value::Object(map)).expect("Failed to parse JSON");
                Self::convert_subnet_devices_to_packets(subnet_devices)
            }
            _ => Vec::new(), // Default case
        };
        self.set_packet_space(&mut tmp_devices, packets);
        /*add*/
        let devices_file_path = format!("{}/results/devices.json", self.file_dir);
        let devices_contents =
            fs::read_to_string(devices_file_path).expect("Error while reading devices.json");

        let device_to_physical: HashMap<String, Vec<String>> =
            serde_json::from_str(&devices_contents).expect("Error while parsing devices.json");

        for (device_name, physical_list) in device_to_physical {
            if let Some(device) = tmp_devices.get_mut(&device_name) {
                device.set_physical_devices(physical_list);
            } else {
                println!("failed to find device in routes for devices.json entry: {:?}", device_name);
            }
        }
        /*add*/
        self.devices = Arc::new(tmp_devices);
        self.src_toponet.set_arc_devices(&self.devices);
    }

    fn set_packet_space(
        &mut self,
        tmp_devices: &mut HashMap<String, Device>,
        packets: Vec<Packet>,
    ) {
        // 仅用于触发 packet_space 绑定逻辑，不做额外聚合。
        for packet in packets {
            let tmp_device_name = packet.host_name.clone();
            let phy = packet.device_name.clone(); //add
            let packet_space = Rule::new_for_packet_space(packet.prefix_len, packet.prefix.clone(),packet.device_name.clone());//add
            // 新增“按 physical device 划分”的 packet space
            let packet_space_phy = Rule::new_for_packet_space(
                packet.prefix_len,
                packet.prefix.clone(),
                phy.clone(),//add
            );
            if let Some(device) = tmp_devices.get_mut(&tmp_device_name) {
                device.set_packet_space_file(packet_space);
                device.add_packet_space_for_physical(phy,packet_space_phy);//add
            } else {
                println!("failed to find packet space device: {:?}", tmp_device_name);
            }
        }
    }

    /// 读取 edge_devices 文件，加载边缘设备集合。
    pub fn get_edge_devices_name(&mut self) {
        let edge_device_file_path = format!("{}/results/edge_devices", self.file_dir);
        let edge_device_file = File::open(edge_device_file_path);
        let reader = BufReader::new(edge_device_file.unwrap());
        for edge_device_name in reader.lines() {
            self.edge_devices.insert(edge_device_name.unwrap());
        }
    }

    /// 读取 topology.json 并注入到 Toponet。
    pub fn init_network(&mut self) {
        let mut tmp_network = Network::new();
        let topology_filepath = format!("{}/results/topology.json", self.file_dir);
        tmp_network.read_topology_by_file(&topology_filepath);
        self.network = Arc::new(tmp_network);
        self.src_toponet.set_arc_network(&self.network);
    }

    pub fn build(&mut self) {
        // build 阶段：加载输入 + 编码规则/packet_space。
        let start_build: Instant = Instant::now();
        self.get_devices_name();
        self.get_edge_devices_name();
        self.read_devices_files();
        self.init_network();

        let start_encode = Instant::now();
        self.src_toponet.build_prefix_bdd_cache();
         let duration_encode_prefix = start_encode.elapsed();
        println!("Prefix encoding time: {:?}", duration_encode_prefix);
        let start_encode = Instant::now();
        // self.src_toponet.encode_rule_to_lec(&self.edge_devices);
        self.src_toponet.encode_rule_to_lec(&self.edge_devices);
        let duration_encode = start_encode.elapsed();
        println!("Rule encoding time: {:?}", duration_encode);
        let start_encode = Instant::now();
        self.src_toponet.encode_packet_space();
        // self.src_toponet.encode_packet_space_by_physical();//add
        let duration_packet = start_encode.elapsed();
        println!("Packet space encoding time: {:?}", duration_packet);
        let duration_build: std::time::Duration = start_build.elapsed();
        println!("Build phase time: {:?}", duration_build);
    }

    pub fn verify(&self) {
        // 以每个目的边缘设备为单位并行执行验证。
        let start_verify: Instant = Instant::now();
        let dst_device_names: Vec<String> = self.edge_devices.iter().cloned().collect();
        let mut base_toponet = self.src_toponet.clone();
        base_toponet.gen_topo_node(&self.devices_name);
        base_toponet.prebuild_nodes_raw_templates();

        dst_device_names.par_iter().for_each(|dst_device_name| {
        // for dst_device_name in &dst_device_names {
            let mut toponet = base_toponet.clone();
            toponet.set_dst_node_name(dst_device_name.clone());
            // let mut toponet = self.gen_topo_net(dst_device_name.clone());
            // toponet.gen_topo_node(&self.devices_name);
            // toponet.prebuild_nodes_raw_templates();
            // let dst_phys=self.src_toponet.get_dst_phys(dst_device_name);
            // dst_phys.par_iter().for_each(|dst_phy| {
            // for dst_phy in dst_phys {
                // let mut t=toponet.clone();
                // toponet.set_dst_phy_name(dst_phy.clone());//add
                toponet.instantiate_nodes_for_current_dst_phy();
                // toponet.node_cal_in_degree();
                toponet.start_count(self.network.as_ref(), &self.edge_devices);
                // toponet.show_result_by_phy(&self.edge_devices);
                // toponet.dfs_fill_node_union(self.network.as_ref());
                // toponet.show_result_src_phy_detail(&self.edge_devices);
                // toponet.show_result_by_phy_union(&self.edge_devices);
                let dst_phys=self.src_toponet.get_dst_phys(dst_device_name);
                for phy in dst_phys{
                    toponet.set_dst_phy_name(phy);
                    toponet.show_result(&self.edge_devices);
                }
            // }
            // );

        }
        );
        let duration_verify = start_verify.elapsed();
        println!("Verification phase time: {:?}", duration_verify);
    }

    pub fn gen_topo_net(&self, dst_device_name: String) -> Toponet {
        // 基于已编码的源 Toponet 克隆，并设置当前目的节点。
        let mut tmp_toponet = self.src_toponet.clone();
        tmp_toponet.set_dst_node_name(dst_device_name.clone());
        tmp_toponet
    }

}
