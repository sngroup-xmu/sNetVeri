use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufReader, Read};

use crate::util::device_port::DevicePort;

#[derive(Debug, Clone, Serialize, Deserialize)]
/// 输入拓扑 JSON 中单条链路记录（src_port <-> dst_port）。
struct Topology {
    dst_node: String,
    dst_port: String,
    src_node: String,
    src_port: String,
}

/// 网络拓扑索引：
/// 1) device_ports: 设备 -> 本设备所有端口
/// 2) topology: 端口 -> 对端端口（双向）
pub struct Network {
    device_ports: HashMap<String, HashSet<DevicePort>>,
    topology: HashMap<DevicePort, DevicePort>,
}

impl Network {
    pub fn new() -> Network {
        Network {
            device_ports: HashMap::new(),
            topology: HashMap::new(),
        }
    }

    /// 增加一条双向链路，并同时更新两端设备的端口集合。
    pub fn add_topology(&mut self, d1: &str, p1: &str, d2: &str, p2: &str) {
        let dp1 = DevicePort::new(d1.to_string(), p1.to_string());
        let dp2 = DevicePort::new(d2.to_string(), p2.to_string());

        self.topology.insert(dp1.clone(), dp2.clone());
        self.topology.insert(dp2.clone(), dp1.clone());

        self.device_ports
            .entry(d1.to_string())
            .or_insert(HashSet::new()) // Insert if not exists
            .insert(dp1.clone());

        self.device_ports
            .entry(d2.to_string())
            .or_insert(HashSet::new()) // Insert if not exists
            .insert(dp2.clone());
    }

    /// 从 topology.json 读取链路并构建索引。
    pub fn read_topology_by_file(&mut self, filepath: &str) {
        let file = File::open(filepath).expect("Error opening the file");
        let mut reader = BufReader::new(file);
        let mut content = String::new();

        reader
            .read_to_string(&mut content)
            .expect("Error reading the file");

        let topologies: Vec<Topology> =
            serde_json::from_str(&content).expect("Error parsing the JSON");

        for topology in topologies {
            let d1 = &topology.src_node;
            let p1 = &topology.src_port;
            let d2 = &topology.dst_node;
            let p2 = &topology.dst_port;

            self.add_topology(d1, p1, d2, p2);
        }
    }

    /// 返回设备到端口集合的只读映射。
    pub fn get_device_ports(&self) -> &HashMap<String, HashSet<DevicePort>> {
        &self.device_ports
    }

    /// 返回端口到对端端口的只读映射。
    pub fn get_toplogy(&self) -> &HashMap<DevicePort, DevicePort> {
        &self.topology
    }
}
