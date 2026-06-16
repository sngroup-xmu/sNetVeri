use crate::verifier::annoucement::Announcement;
use biodivine_lib_bdd::Bdd;

#[derive(Clone)]
/// BFS 队列中的上下文：当前设备 + 当前通告。
pub struct Ctx {
    device_name: String,
    announcement: Announcement,
}

impl Ctx {
    /// 创建上下文对象。
    pub fn new(device_name: String, predicate: Bdd, count: i32) -> Self {
        let announcement = Announcement::new(predicate, count);
        Ctx {
            device_name,
            announcement,
        }
    }

    pub fn set_device_name(&mut self, new_device_name: String) {
        self.device_name = new_device_name;
    }

    pub fn get_device_name(&self) -> &String {
        &self.device_name
    }

    pub fn set_announcement(&mut self, new_announcement: Announcement) {
        self.announcement = new_announcement;
    }

    pub fn get_announcement(&self) -> &Announcement {
        &self.announcement
    }
}
