#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path
import matplotlib.pyplot as plt
STYLE = {
    "HUOMIL": dict(color="#4D4D4D", marker="o", linestyle="-", linewidth=1.8),
    "EUOL-Miner_Base": dict(color="#1F77B4", marker="s", linestyle="--", linewidth=1.8),
    "EUOL-Miner_PS": dict(color="#FF7F0E", marker="^", linestyle="-.", linewidth=1.8),
    "EUOL-Miner_PEUO": dict(color="#2CA02C", marker="D", linestyle=":", linewidth=1.8),
    "EUOL-Miner_Dual": dict(color="#D62728", marker="X", linestyle="-", linewidth=2.0),
}
DISPLAY_NAME = {
    "HUOMIL": "HUOMIL",
    "EUOL-Miner_Base": r"EUOL-Miner$_{\mathrm{Base}}$",
    "EUOL-Miner_PS": r"EUOL-Miner$_{\mathrm{PS}}$",
    "EUOL-Miner_PEUO": r"EUOL-Miner$_{\mathrm{PEUO}}$",
    "EUOL-Miner_Dual": r"EUOL-Miner$_{\mathrm{Dual}}$",
}

ALG_ORDER = ["HUOMIL", "EUOL-Miner_Base", "EUOL-Miner_PS", "EUOL-Miner_PEUO", "EUOL-Miner_Dual"]


def to_float(x):
    try:
        return float(x)
    except Exception:
        return None

def load_rows(path):
    with open(path, newline='', encoding='utf-8') as f:
        return list(csv.DictReader(f))
    
def fmt_num(x):
    try:
        v = float(x)
        return f"{v:g}"
    except Exception:
        return str(x)

def unique_values(rows, col):
    vals = []
    for r in rows:
        v = r.get(col)
        if v not in (None, "") and v not in vals:
            vals.append(v)
    return vals

def make_setting_title(rows, setting):
    gammas = unique_values(rows, "gamma_pct")
    deltas = unique_values(rows, "delta")
    


    if setting == "fixedG":
        gamma = fmt_num(gammas[0]) if gammas else "?"
        return rf"fixed $\gamma={gamma}\%$, varied $\delta$"

    if setting == "fixedD":
        delta = fmt_num(deltas[0]) if deltas else "?"
        return rf"fixed $\delta={delta}$, varied $\gamma$"

    if setting == "fixedPair":
        gamma = fmt_num(gammas[0]) if gammas else "?"
        delta = fmt_num(deltas[0]) if deltas else "?"
        return rf"$\gamma={gamma}\%$, $\delta={delta}$"

    gamma_text = ",".join(fmt_num(v) for v in gammas)
    delta_text = ",".join(fmt_num(v) for v in deltas)
    return rf" \in {{{gamma_text}}}\%$,  \in {{{delta_text}}}$"



def main():
    ap = argparse.ArgumentParser(description="Plot runtime or memory for one dataset/setting from EUOL-Miner CSV.")
    ap.add_argument("--csv", required=True, help="Input CSV, e.g., euol_runtime_memory_all_plot_data.csv")
    ap.add_argument("--dataset", help="Dataset name, e.g., retail, bms2, T40I10D100K")
    ap.add_argument("--family", help="Data family for scalability, e.g., T10I4D")
    ap.add_argument("--setting", required=True, choices=["fixedD", "fixedG", "fixedPair"])
    ap.add_argument("--metric", required=True, choices=["runtime", "memory"])
    ap.add_argument("--out", required=True, help="Output image path, e.g., retail_fixedD_runtime.png")
    args = ap.parse_args()

    metric_col = "runtime_s" if args.metric == "runtime" else "memory_mb"
    ylabel = "Total runtime (s)" if args.metric == "runtime" else "Peak memory (MB)"

    rows = load_rows(args.csv)
    rows = [r for r in rows if r.get("setting_type") == args.setting]
    if args.dataset:
        rows = [r for r in rows if r.get("dataset") == args.dataset]
        title_target = args.dataset
    elif args.family:
        rows = [r for r in rows if r.get("data_family") == args.family]
        title_target = args.family
    else:
        raise SystemExit("Please provide either --dataset or --family.")

    # Remove rows without selected metric
    rows = [r for r in rows if to_float(r.get(metric_col)) is not None]
    if not rows:
        raise SystemExit("No matching rows found. Check dataset/family, setting, and metric.")

    # Group by algorithm
    by_alg = {alg: [] for alg in ALG_ORDER}
    for r in rows:
        alg = r.get("algorithm")
        if alg in by_alg:
            x = to_float(r.get("x_value"))
            y = to_float(r.get(metric_col))

            if args.setting == "fixedD":
                 # fixed δ, varied γ → x-axis is γ, so add %
                x_label = f"{fmt_num(r.get('gamma_pct'))}%"
            elif args.setting == "fixedG":
                # fixed γ, varied δ → x-axis is δ, so no %
                 x_label = fmt_num(r.get("delta"))      
            elif args.setting == "fixedPair":
                x_label = r.get("dataset")
            else:
                x_label = f"{fmt_num(r.get('gamma_pct'))}%, {fmt_num(r.get('delta'))}"

        by_alg[alg].append((x, x_label, y))

    # Sort by x. For fixedD/fixedG, show stricter-to-looser thresholds by descending x.
    reverse = args.setting in ("fixedD", "fixedG")
    for alg in by_alg:
        by_alg[alg].sort(key=lambda t: t[0], reverse=reverse)

    # Common labels from first non-empty algorithm
    labels = None
    xs = None
    for alg in ALG_ORDER:
        if by_alg[alg]:
            labels = [t[1] for t in by_alg[alg]]
            xs = list(range(len(labels)))
            break
    if labels is None:
        raise SystemExit("No algorithm data available after filtering.")

    fig, ax = plt.subplots(figsize=(7.5, 4.8))
    for alg in ALG_ORDER:
        data = by_alg[alg]
        if not data:
            continue
        # Align by labels in case one algorithm is missing for some x-values
        dmap = {label: y for _, label, y in data}
        yvals = [dmap.get(label) for label in labels]
        valid_x = [i for i, y in enumerate(yvals) if y is not None]
        valid_y = [y for y in yvals if y is not None]
        if valid_y:
            #ax.plot(valid_x, valid_y, label=alg.replace("EUOL-Miner_", ""), **STYLE[alg])
            ax.plot(valid_x, valid_y, label=DISPLAY_NAME.get(alg, alg), **STYLE[alg])

    ax.set_xticks(xs)
    ax.set_xticks(xs)
    ax.set_xticklabels(labels, rotation=0, ha="center", va="top", fontsize=9)

    for label in ax.get_xticklabels():
     label.set_horizontalalignment("center")

    ax.tick_params(axis="x", which="major", length=5, width=1, direction="out", pad=6)
    #ax.set_xlabel("Minimum support thresholds" if args.setting == "fixedG" else "Minimum utility occupancy threshold")
    #ax.set_xlabel("Minimum utility occupancy threshold" if args.setting == "fixedG" else "Dataset size")
    #ax.set_xlabel("Threshold setting" if args.setting != "fixedPair" else "Dataset size")
    if args.setting == "fixedD":
        ax.set_xlabel("Minimum support thresholds (γ)")
    elif args.setting == "fixedG":
        ax.set_xlabel("Minimum utility occupancy threshold (δ)")
    elif args.setting == "fixedPair":
        ax.set_xlabel("Dataset")
    else:
        ax.set_xlabel(r"$(\gamma,\delta)$")
    ax.set_ylabel(ylabel)
    
    setting_title = make_setting_title(rows, args.setting)
    ax.set_title(f"{ylabel} on {title_target} ({setting_title})")
    ax.grid(True, linewidth=0.4, alpha=0.35)
    #ax.legend(fontsize=8, frameon=True)
    fig.tight_layout()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=300, bbox_inches="tight")
    print("Saved: {out}")

if __name__ == "__main__":
    main()
