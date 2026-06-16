import argparse
import json
import os
import sys
import time

from pybatfish.client.session import Session
from pybatfish.datamodel.flow import PathConstraints


def check_reachability(snapshot_path: str, type: str):
    """
    检查 snapshot 中 source → dest 的可达性，
    并将结果输出到 snapshot_path/output.txt 中，
    同时记录执行时间。
    """

    start_time = time.time()
    network_name="custom_network"
    # 初始化 Batfish
    bf = Session(host="localhost")
    bf.set_network(network_name)
    bf.init_snapshot(snapshot_path, name=network_name, overwrite=True)
    if type == "simulation":
        bf.q.bgpRib().answer().frame()
    issues_df = bf.q.initIssues().answer().frame()
    # 读取 config_result.json
    result_file = os.path.join(snapshot_path, "config_result.json")
    if not os.path.exists(result_file):
        raise FileNotFoundError(f"{result_file} not found")
    if type == "verification":
        with open(result_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        source_set = set(data["source"])
        dest_set = set(data["dest"])

    # 输出文件
    output_file = os.path.join(snapshot_path, "output.txt")

    with open(output_file, "w", encoding="utf-8") as out:
        # if issues_df.empty:
        #     out.write("No init issues detected.\n\n")
        # else:
        #     out.write(issues_df.to_string(index=False))
        #     out.write("\n\n")
        if type == "verification":
            out.write(f"Source Set: {source_set}\n")
            out.write(f"Dest Set: {dest_set}\n\n")

            for src in source_set:
                for dst in dest_set:

                    check_start = time.time()

                    out.write(f"Checking reachability: {src} → {dst}\n")

                    answer = bf.q.reachability(
                        pathConstraints=PathConstraints(
                            startLocation=src,
                            endLocation=dst
                        ),
                        actions="SUCCESS"
                    ).answer()

                    df = answer.frame()

                    if df.empty:
                        out.write("Unreachable\n\n")
                    else:
                        out.write("Reachable\n")
                        out.write(f"Trace count: {len(df.Traces)}\n\n")

                    check_end = time.time()
                    out.write(f"Time for this pair: {check_end - check_start:.4f} seconds\n\n")

        total_time = time.time() - start_time
        out.write(f"\nTotal execution time: {total_time:.4f} S\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Run Batfish check"
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to snapshot directory"
    )

    parser.add_argument(
        "--type",
        required=True,
        default="verification",
        choices=["simulation", "verification"],
        help="type of Batfish check"
    )

    args = parser.parse_args()
    input_dir = args.input
    type = args.type
    # 用户输入 snapshot_path
    check_reachability(input_dir,type=type)