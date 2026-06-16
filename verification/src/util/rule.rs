use crate::util::forward_action::ForwardAction;
use std::hash::{Hash, Hasher};

#[derive(Clone)]
/// 路由规则：前缀匹配 + 转发动作。
pub struct Rule {
    forward_action: ForwardAction,
    prefix_len: usize,
    ip: String,
    physical_devices: Vec<String>,//add
}

impl Rule {
    /// 从路由表记录创建普通规则。
    pub fn new(prefix_len: usize, ip: String, forward_type: String, ports: Vec<String>,physical_devices:Vec<String>) -> Self {
        let forward_action: ForwardAction = ForwardAction::new(forward_type, ports,physical_devices.clone());
        Rule {
            forward_action: forward_action,
            prefix_len: prefix_len,
            ip: ip,
            physical_devices: physical_devices, //add
        }
    }
    
    /// 为 packet_space.json 生成专用规则（仅携带前缀，不含转发端口）。
    pub fn new_for_packet_space(prefix_len: usize, ip: String,device_name:String) -> Self {
        let forward_action: ForwardAction =
            ForwardAction::new("packet_space".to_string(), Vec::new(),vec![device_name.clone()]);
        Rule {
            forward_action: forward_action,
            prefix_len: prefix_len,
            ip: ip,
            physical_devices:vec![device_name.clone()], //add
        }
    }

    pub fn get_prefix_len(&self) -> usize {
        self.prefix_len
    }

    pub fn get_ip(&self) -> &str {
        &self.ip
    }

    pub fn get_forward_action(&self) -> &ForwardAction {
        // 返回 clone，避免调用方持有内部可变引用。
        &self.forward_action
    }

    pub fn get_physical_devices(&self) -> &Vec<String> {
        &self.physical_devices
    } //add
}

impl Hash for Rule {
    fn hash<H: Hasher>(&self, state: &mut H) {
        // Hash the fields that contribute to the equality and uniqueness of the Rule
        self.forward_action.hash(state);
        self.prefix_len.hash(state);
        self.ip.hash(state);
        self.physical_devices.hash(state);//add
    }
}

impl Eq for Rule {}

impl PartialEq for Rule {
    fn eq(&self, other: &Self) -> bool {
        // Implement custom equality comparison based on the fields
        self.forward_action == other.forward_action
            && self.prefix_len == other.prefix_len
            && self.ip == other.ip
        && self.physical_devices == other.physical_devices //add
    }
}
