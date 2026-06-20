#!/usr/bin/env python3
import csv
from pathlib import Path

import matplotlib.pyplot as plt


# =========================
# Config
# =========================
CSV_PATH = "synthetic_.csv"
OUT_DIR = Path("figures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

DATASETS = [
    "T10I4D200K",
    "T10I4D400K",
    "T10I4D600K",
    "T10I4D800K",
    "T10I4D1000K",
]

X_LABELS = ["200K", "400K", "600K", "800K", "1000K"]

GAMMA_PCT = 0.05
DELTA = 0.25

ALG_ORDER = [
    "HUOMIL",
    "EUOL-Miner_Base",
    "EUOL-Miner_PS",
    "EUOL-Miner_PEUO",
    "EUOL-Miner_Dual",
]

DISPLAY_NAME = {
    "HUOMIL": r"HUOMIL",
    "EUOL-Miner_Base": r"EUOL-Miner$_{\mathrm{Base}}$",
    "EUOL-Miner_PS": r"EUOL-Miner$_{\mathrm{PS}}$",
    "EUOL-Miner_PEUO": r"EUOL-Miner$_{\mathrm{PEUO}}$",
    "EUOL-Miner_Dual": r"EUOL-Miner$_{\mathrm{Dual}}$",
}

STYLE = {
    "HUOMIL": dict(color="#4D4D4D", marker="o", linestyle="-", linewidth=0.95),
    "EUOL-Miner_Base": dict(color="#1F77B4", marker="s", linestyle="--", linewidth=0.95),
    "EUOL-Miner_PS": dict(color="#FF7F0E", marker="^", linestyle="-.", linewidth=0.95),
    "EUOL-Miner_PEUO": dict(color="#2CA02C", marker="D", linestyle=":", linewidth=0.95),
    "EUOL-Miner_Dual": dict(color="#D62728", marker="X", linestyle="-", linewidth=1.05),
}


# =========================
# IEEE one-column style
# =========================
plt.rcParams.update({
    "font.family": "Times New Roman",
    "font.size": 6.8,
    "axes.labelsize": 7.2,
    "xtick.labelsize": 6.4,
    "ytick.labelsize": 6.4,
    "legend.fontsize": 6.1,
    "pdf.fonttype": 42,
    "ps.fonttype": 42,
})


# =========================
# Helper functions
# =========================
def to_float(x):
    try:
        return float(x)
    except Exception:
        return None


def same_float(a, b, eps=1e-9):
    a = to_float(a)
    b = to_float(b)
    if a is None or b is None:
        return False
    return abs(a - b) <= eps


def load_rows(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def filter_rows(rows):
    filtered = []

    dataset_set = set(DATASETS)

    for r in rows:
        dataset = r.get("dataset")
        alg = r.get("algorithm")

        if dataset not in dataset_set:
            continue

        if alg not in ALG_ORDER:
            continue

        if not same_float(r.get("gamma_pct"), GAMMA_PCT):
            continue

        if not same_float(r.get("delta"), DELTA):
            continue

        runtime = to_float(r.get("runtime_s"))
        memory = to_float(r.get("memory_mb"))

        if runtime is None and memory is None:
            continue

        filtered.append(r)

    return filtered


def build_metric_by_alg(rows, metric_col):
    by_alg = {alg: {} for alg in ALG_ORDER}

    for r in rows:
        alg = r.get("algorithm")
        dataset = r.get("dataset")
        value = to_float(r.get(metric_col))

        if alg in by_alg and dataset in DATASETS and value is not None:
            by_alg[alg][dataset] = value

    return by_alg


def plot_metric(ax, by_alg, ylabel):
    xs = list(range(len(DATASETS)))

    for alg in ALG_ORDER:
        yvals = [by_alg[alg].get(ds) for ds in DATASETS]

        valid_x = [i for i, y in enumerate(yvals) if y is not None]
        valid_y = [y for y in yvals if y is not None]

        if valid_y:
            ax.plot(
                valid_x,
                valid_y,
                label=DISPLAY_NAME[alg],
                markersize=3.2,
                **STYLE[alg],
            )

    ax.set_xticks(xs)
    ax.set_xticklabels(X_LABELS, rotation=0, ha="center", fontstyle="normal")
    ax.set_xlabel("Dataset size")
    ax.set_ylabel(ylabel)

    ax.grid(True, linewidth=0.25, alpha=0.35, linestyle="--")

    for spine in ax.spines.values():
        spine.set_linewidth(0.45)

    ax.tick_params(axis="both", width=0.45, length=2.5, pad=2)


# =========================
# Main
# =========================
rows = load_rows(CSV_PATH)
rows = filter_rows(rows)

if not rows:
    raise SystemExit(
        "No matching rows found. Please check dataset names, gamma_pct=0.05, delta=0.25, and CSV columns."
    )

runtime_by_alg = build_metric_by_alg(rows, "runtime_s")
memory_by_alg = build_metric_by_alg(rows, "memory_mb")

fig, axes = plt.subplots(
    2,
    1,
    figsize=(3.45, 4.55),
    dpi=300,
)

# Panel (a): runtime
plot_metric(
    axes[0],
    runtime_by_alg,
    "Total runtime (s)",
)

axes[0].text(
    0.5,
    -0.24,
    r"(a) Runtime under fixed $\gamma$=0.05% and fixed $\delta$=0.25.",
    ha="center",
    va="top",
    fontsize=6.5,
    transform=axes[0].transAxes,
)

# Panel (b): memory
plot_metric(
    axes[1],
    memory_by_alg,
    "Peak memory (MB)",
)

axes[1].text(
    0.5,
    -0.24,
    r"(b) Memory under fixed $\gamma$=0.05% and fixed $\delta$=0.25.",
    ha="center",
    va="top",
    fontsize=6.5,
    transform=axes[1].transAxes,
)

# Shared legend at bottom
handles, labels = axes[0].get_legend_handles_labels()

legend = fig.legend(
    handles,
    labels,
    loc="lower center",
    bbox_to_anchor=(0.58, -0.03),
    ncol=2,
    frameon=True,
    fancybox=False,
    edgecolor="black",
    fontsize=6.1,
    handlelength=2.0,
    columnspacing=0.9,
    handletextpad=0.4,
    borderpad=0.25,
)

legend.get_frame().set_linewidth(0.45)
legend.get_frame().set_facecolor("white")

fig.subplots_adjust(
    left=0.18,
    right=0.98,
    top=0.985,
    bottom=0.145,
    hspace=0.40,
)

png_path = OUT_DIR / "fig8.png"
pdf_path = OUT_DIR / "fig8.pdf"

fig.savefig(png_path, dpi=600, bbox_inches="tight")
fig.savefig(pdf_path, bbox_inches="tight")

plt.close(fig)

print("Saved:")
print(png_path)
print(pdf_path)