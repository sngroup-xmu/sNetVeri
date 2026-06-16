use std::fmt;
use std::hash::{Hash, Hasher};
use biodivine_lib_bdd::Bdd;

#[derive(Debug, Clone)]
/// 转发动作：包含动作类型和下一跳端口列表。
pub struct ForwardAction {
    forward_type: String,
    ports: Vec<String>,
    devices: Vec<String>,
}

impl ForwardAction {
    /// 创建转发动作。
    pub fn new(forward_type: String, ports: Vec<String>,devices:Vec<String>) -> Self {
        ForwardAction {
            forward_type,
            ports: ports,
            devices: devices,
        }
    }

    pub fn get_devices(&self) -> &Vec<String> {
        &self.devices
    }
    /// 获取动作类型（如 forward、drop、packet_space 等）。
    pub fn get_forward_type(&self) -> &String {
        &self.forward_type
    }

    /// 获取动作关联端口集合。
    pub fn get_ports(&self) -> &Vec<String> {
        &self.ports
    }
}

impl Hash for ForwardAction {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.forward_type.hash(state);
        self.ports.hash(state);
        self.devices.hash(state);
    }
}

impl PartialEq for ForwardAction {
    fn eq(&self, other: &Self) -> bool {
        self.forward_type == other.forward_type && self.ports == other.ports
            && self.devices == other.devices
    }
}

impl Eq for ForwardAction {}

impl fmt::Display for ForwardAction {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // 用于日志输出，便于定位规则对应的转发行为。
        write!(
            f,
            "ForwardAction {{ forward_type: {}, ports: {:?} }}",
            self.forward_type, self.ports
        )
    }
}
