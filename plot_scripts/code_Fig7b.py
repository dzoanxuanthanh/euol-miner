#!/usr/bin/env python3
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

OUT_DIR = Path("figures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# =========================
# IEEE one-column style
# =========================
plt.rcParams.update({
    "font.family": "Times New Roman",
    "font.size": 6.5,
    "axes.labelsize": 7.0,
    "xtick.labelsize": 6.2,
    "ytick.labelsize": 6.2,
    "legend.fontsize": 6.2,
    "pdf.fonttype": 42,
    "ps.fonttype": 42,
    "hatch.linewidth": 0.25,
})

# =========================
# 1. READ AND MERGE DATA
# =========================
df_real = pd.read_csv("real_.csv")
df_syn = pd.read_csv("synthetic_all.csv")

df_all = pd.concat([df_real, df_syn], ignore_index=True)

df_agg = (
    df_all
    .groupby(["dataset", "algorithm"])[["ubuo_topk_calls", "ubuo_early_pass"]]
    .sum()
    .reset_index()
)

dual_df = df_agg[df_agg["algorithm"] == "EUOL-Miner_Dual"].set_index("dataset")

datasets_raw = ["connect", "mushroom", "retail", "chess", "T40I10D300K"]
labels = ["Connect", "Mushroom", "Retail", "Chess", "T40I10D300K"]

dual_df = dual_df.reindex(datasets_raw)

total_calls = dual_df["ubuo_topk_calls"]
avoidance_calls = dual_df["ubuo_early_pass"]
full_eval_calls = total_calls - avoidance_calls

avoidance_ratio = (avoidance_calls / total_calls) * 100
full_eval_ratio = (full_eval_calls / total_calls) * 100

# =========================
# 2. HELPER FUNCTIONS
# =========================
def format_short(n):
    n = float(n)
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return f"{int(n)}"


# =========================
# 3. PLOT
# =========================
x = np.arange(len(datasets_raw))
bar_width = 0.46

# IEEE one-column width: about 3.4--3.5 inches
fig, ax = plt.subplots(figsize=(3.45, 2.55), dpi=300)

bar_bottom = ax.bar(
    x,
    full_eval_ratio,
    bar_width,
    label="Full evaluation",
    color="#FDD0A2",
    edgecolor="black",
    linewidth=0.45,
)

bar_top = ax.bar(
    x,
    avoidance_ratio,
    bar_width,
    bottom=full_eval_ratio,
    label=r"UBUO$^+$ early-pass",
    color="#9DCDEF",
    edgecolor="black",
    hatch="....",
    linewidth=0.45,
)

# =========================
# 4. AXIS SETTINGS
# =========================
ax.set_ylim(0, 115)

ax.set_ylabel("Candidate evaluations (%)", labelpad=2)
ax.set_xlabel("Dataset", labelpad=2)

ax.set_xticks(x)
ax.set_xticklabels(
    labels,
    rotation=0,
    ha="center",
    fontstyle="normal",
    fontweight="normal",
)

for label in ax.get_xticklabels():
    label.set_fontstyle("normal")
    label.set_fontweight("normal")

ax.yaxis.set_major_formatter(ticker.PercentFormatter(xmax=100))

ax.grid(axis="y", linewidth=0.25, alpha=0.35, linestyle="--")

for spine in ax.spines.values():
    spine.set_linewidth(0.45)

ax.tick_params(axis="both", width=0.45, length=2.5, pad=2)

# Compact legend
legend = ax.legend(
    loc="upper center",
    bbox_to_anchor=(0.5, 1.02),
    ncol=2,
    frameon=True,
    fancybox=False,
    edgecolor="black",
    borderpad=0.25,
    handlelength=1.25,
    handletextpad=0.35,
    columnspacing=0.75,
)

legend.get_frame().set_linewidth(0.45)
legend.get_frame().set_facecolor("white")

# =========================
# 5. TEXT LABELS
# =========================
for i in range(len(datasets_raw)):
    # Full evaluation text
    ax.text(
       x[i],
    full_eval_ratio.iloc[i] / 2,
    f"{full_eval_ratio.iloc[i]:.1f}%\n({format_short(full_eval_calls.iloc[i])})",
    ha="center",
    va="center",
    color="black",
    fontsize=5.6,
    fontweight="normal",
    linespacing=0.9,
    bbox=dict(
        facecolor="white",
        alpha=1.0,
        edgecolor="none",
        pad=0.8,
    ),
    )

    # Early-pass text
    y_top_text = full_eval_ratio.iloc[i] + avoidance_ratio.iloc[i] / 2

    ax.text(
       x[i],
    y_top_text,
    f"{avoidance_ratio.iloc[i]:.1f}%\n({format_short(avoidance_calls.iloc[i])})",
    ha="center",
    va="center",
    color="black",
    fontsize=5.4,
    fontweight="normal",
    linespacing=0.9,
    bbox=dict(
        facecolor="white",
        alpha=1.0,
        edgecolor="none",
        pad=0.8,
    ),
    )

    # 100% label on top
    ax.text(
        x[i],
        101.5,
        "100%",
        ha="center",
        va="bottom",
        color="black",
        fontsize=5.4,
        fontstyle="normal",
    )

# No title inside the figure; use the Word caption instead.
fig.tight_layout(pad=0.35)

# =========================
# 6. SAVE
# =========================
fig.savefig(
    OUT_DIR / "fig7b.png",
    dpi=600,
    bbox_inches="tight",
)
fig.savefig(
    OUT_DIR / "fig7b.pdf",
    bbox_inches="tight",
)

print("Saved:")
print(OUT_DIR / "fig7b.png")
print(OUT_DIR / "fig7b.pdf")