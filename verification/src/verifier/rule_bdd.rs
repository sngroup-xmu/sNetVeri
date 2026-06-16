use biodivine_lib_bdd::*;

#[derive(Clone)]
/// 规则编码后的 BDD 中间结构（当前代码仅部分路径使用）。
pub struct RuleBDD {
    hit: Bdd,
    tmatch: Bdd,
    lec_index: i32,
    black_list: Vec<Bdd>,
}

impl RuleBDD {
    /// 创建规则 BDD 表示：
    /// - hit: 首次命中区域
    /// - tmatch: 匹配区域
    /// - lec_index: 对应的 LEC 索引
    pub fn new(hit: Bdd, tmatch: Bdd, lec_index: i32) -> Self {
        RuleBDD {
            hit: hit,
            tmatch: tmatch,
            lec_index: lec_index,
            black_list: Vec::new(),
        }
    }

    /// 判断两条规则命中区域是否相同。
    pub fn compare_with_other_rule_bdd(&self, o_rule_bdd: &RuleBDD) -> bool {
        let result = self.hit.xor(o_rule_bdd.get_hit());
        result.is_false()
    }

    pub fn get_hit(&self) -> &Bdd {
        &self.hit
    }

    pub fn get_match(&self) -> &Bdd {
        &self.tmatch
    }

    pub fn get_blacklist(&self) -> &Vec<Bdd> {
        &self.black_list
    }

    pub fn set_hit(&mut self, bdd: Bdd) {
        self.hit = bdd;
    }

    pub fn add_blacklist(&mut self, bdd: Bdd) {
        self.black_list.push(bdd);
    }
}
