use std::collections::HashMap;
use crate::util::rule::Rule;
use serde::{Deserialize, Serialize};
use std::fs;

#[derive(Clone)]
/// 设备对象：保存设备名、路由规则以及该设备对应的 packet space。
pub struct Device {
    name: String,
    rules: Vec<Rule>,
    packet_space: Option<Rule>,
    physical_devices:Vec<String>, //add
    packet_space_by_physical: HashMap<String, Vec<Rule>>, // add

}

#[derive(Serialize, Deserialize, Debug)]
/// 设备路由文件中的单条 JSON 记录。
struct Record {
    #[serde(alias = "forward_type")]
    action: String,
    #[serde(alias = "ip")]
    prefix: String,
    #[serde(alias = "ports")]
    nexthop_infs: Vec<String>,
    prefix_len: usize,
    #[serde(alias = "devices", default)] // add
    devices: Vec<String>,
}

impl Device {
    pub fn new(name: String) -> Self {
        Device {
            name,
            rules: Vec::new(),
            packet_space: None,
            physical_devices: Vec::new(),//add
            packet_space_by_physical: HashMap::new(), //add
        }
    }

    pub fn read_rules_file(&mut self, filename: &String) {
        let contents = fs::read_to_string(filename).expect("Error while reading the file");
        let records: Vec<Record> = serde_json::from_str(&contents).unwrap_or_else(|err| {
            panic!("Error while parsing the JSON {}: {}", filename, err);
        });

        for record in records {
            // 输入规则转换为内部 Rule 表示。
            let rule = Rule::new(
                record.prefix_len,
                record.prefix,
                record.action,
                record.nexthop_infs,
                record.devices,//add
            );
            self.rules.push(rule);
        }
        // 按最长前缀优先排序，保证后续命中计算遵循 LPM 语义。
        self.rules
            .sort_by_key(|rule| std::cmp::Reverse(rule.get_prefix_len()));
    }

    pub fn set_packet_space_file(&mut self, packet_space: Rule) {
        self.packet_space = Some(packet_space);
    }
    
    pub fn set_physical_devices(&mut self, physical_devices: Vec<String>) {
        self.physical_devices = physical_devices;
    }//add
    
    pub fn get_physical_devices(&self) -> &Vec<String> {
        &self.physical_devices
    }//add
    // add：给某个 physical device 增加一条 packet space 规则
    pub fn add_packet_space_for_physical(&mut self, physical_device: String, packet_space: Rule) {
        self.packet_space_by_physical
            .entry(physical_device)
            .or_insert_with(Vec::new)
            .push(packet_space);
    }

    // add：获取按 physical device 划分的 packet space
    pub fn get_packet_space_by_physical(&self) -> &HashMap<String, Vec<Rule>> {
        &self.packet_space_by_physical
    }

    pub fn get_packet_space(&self) -> &Option<Rule> {
        &self.packet_space
    }

    pub fn get_name(&self) -> &String {
        &self.name
    }

    pub fn get_rules(&self) -> &Vec<Rule> {
        &self.rules
    }

    pub fn get_rules_mut(&mut self) -> &mut Vec<Rule> {
        &mut self.rules
    }
}
