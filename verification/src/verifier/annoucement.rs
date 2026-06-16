use biodivine_lib_bdd::Bdd;
#[derive(Clone)]
/// BFS 传播时携带的通告：谓词 + 是否满足计数条件标记。
pub struct Announcement {
    predicate: Bdd,
    count: i32,
}

impl Announcement {
    /// 创建通告对象。
    pub fn new(predicate: Bdd, count: i32) -> Self {
        Announcement { predicate, count }
    }

    /// 获取通告中的谓词（BDD）。
    pub fn get_predicate(&self) -> &Bdd {
        &self.predicate
    }

    /// 获取通告的计数标记。
    pub fn get_count(&self) -> i32 {
        self.count
    }
}
