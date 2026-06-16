#[derive(Debug, PartialEq, Eq, Hash, Clone)]
/// 标识“设备 + 端口”的二元组，作为拓扑边的端点键。
pub struct DevicePort {
    device_name: String,
    port_name: String,
}

impl DevicePort {
    /// 构造一个设备端口标识。
    pub fn new(device_name: String, port_name: String) -> DevicePort {
        DevicePort {
            device_name,
            port_name,
        }
    }

    /// 获取设备名称（返回拷贝，避免暴露内部可变性）。
    pub fn get_device_name(&self) -> &String {
        &self.device_name
    }

    /// 获取端口名称（返回拷贝，避免暴露内部可变性）。
    pub fn get_port_name(&self) -> &String {
        &self.port_name
    }
}
