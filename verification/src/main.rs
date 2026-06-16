mod topo_runner;
mod util;

mod verifier;
use std::env;
use crate::topo_runner::TopoRunner;
use std::time::Instant;
#[macro_use]
extern crate lazy_static;
use std::sync::atomic::{AtomicUsize, Ordering};

// 全局可达/不可达计数器：统计源-宿边缘设备对结果。
lazy_static! {
    pub static ref EXIST_COUNT: AtomicUsize = AtomicUsize::new(0);
    pub static ref NONEXIST_COUNT: AtomicUsize = AtomicUsize::new(0);
}
fn parse_filedir_arg() -> String {
    let mut args = env::args().skip(1);
    let mut filedir: Option<String> = None;

    while let Some(arg) = args.next() {
        if arg == "--filedir" {
            let v = args
                .next()
                .unwrap_or_else(|| panic!("--filedir requires a path"));
            filedir = Some(v);
        }
    }

    filedir.unwrap_or_else(|| "../data/fattree-4-HNM".to_string())
}
fn main() {
    // 固定 rayon 全局线程池规模，保证并行行为可预期。
    rayon::ThreadPoolBuilder::new()
        .num_threads(40)
        .build_global()
        .expect("Failed to build rayon thread pool");
    // 从命令行读取 filedir：cargo run -- <filedir>
    let filedir = parse_filedir_arg();

    // 初始化运行器并指定地址空间位宽（IPv6 使用 128 位）。
    let mut topo_runner: TopoRunner = TopoRunner::new(128);
    // 这里默认读取 data/fattree4 数据集，可按需替换。
    // let filedir = "../data/fattree-70-HNM-multi";
    let start: Instant = Instant::now();
    topo_runner.set_file_dir(&filedir);

    // build 阶段：加载输入文件并编码为 BDD/LEC。
    topo_runner.build();
    // verify 阶段：按每个目的边缘设备并行做可达性验证。
    topo_runner.verify();

    let duration: std::time::Duration = start.elapsed();
    println!("Total program execution time: {:?}", duration);
    println!(
        "Reachable node pair count:  {}",
        EXIST_COUNT.load(Ordering::SeqCst)
    );
    println!(
        "Unreachable node pair count:  {}",
        NONEXIST_COUNT.load(Ordering::SeqCst)
    );
    println!(
        "Total node pair count:  {}",
        EXIST_COUNT.load(Ordering::SeqCst) + NONEXIST_COUNT.load(Ordering::SeqCst)
    );
}
