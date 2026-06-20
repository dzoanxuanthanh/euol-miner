#!/usr/bin/env python3
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.path import Path as MplPath
from matplotlib.patches import PathPatch

OUT_DIR = Path("figures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# =========================
# IEEE one-column font style
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
# Data
# =========================
data = [
    ["Retail", 3820303, 403965],
    ["BMS2", 6698321, 3706092],
    ["T40I10D300K", 162320243, 19615529],
    ["T10I4D1000K", 3474049, 544202],
]

df = pd.DataFrame(data, columns=["Dataset", "Base candidates", "Dual candidates"])
df["Pruned candidates"] = df["Base candidates"] - df["Dual candidates"]
df["Reduction rate"] = df["Pruned candidates"] / df["Base candidates"] * 100

# =========================
# Style
# =========================
COLOR = {
    "Base": "#9DCDEF",
    "Dual": "#F37F7F",
}

HATCH = {
    "Base": "////",
    "Dual": "....",
}


def fmt_int(n):
    return f"{int(n):,}"


def fmt_short(n):
    n = float(n)
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return f"{int(n)}"


def draw_vertical_brace(ax, x, y0, y1, width=0.045, lw=0.65, color="black"):
    """
    Draw a vertical pointy brace from y0 to y1 at position x.
    Suitable for log-scale axes and compact one-column figures.
    """
    if y1 < y0:
        y0, y1 = y1, y0

    is_log = ax.get_yscale() == "log"
    ym = (y0 * y1) ** 0.5 if is_log else (y0 + y1) / 2

    if is_log:
        notch_factor = (y1 / y0) ** 0.04
        ym_upper = ym * notch_factor
        ym_lower = ym / notch_factor
    else:
        span = (y1 - y0) * 0.04
        ym_upper = ym + span
        ym_lower = ym - span

    verts = [
        (x, y1),
        (x + width, y1),
        (x + width, ym_upper),
        (x + width * 1.8, ym),
        (x + width, ym_lower),
        (x + width, y0),
        (x, y0),
    ]

    codes = [MplPath.MOVETO] + [MplPath.LINETO] * 6

    patch = PathPatch(
        MplPath(verts, codes),
        fill=False,
        lw=lw,
        color=color,
        capstyle="round",
        joinstyle="round",
    )
    ax.add_patch(patch)


# =========================
# Plot
# =========================
x = np.arange(len(df))
bar_width = 0.20

# IEEE one-column width: about 3.4--3.5 inches
fig, ax = plt.subplots(figsize=(3.45, 2.75))

base_x = x - bar_width / 2
dual_x = x + bar_width / 2

ax.bar(
    base_x,
    df["Base candidates"],
    width=bar_width,
    label=r"EUOL-Miner$_{\mathrm{Base}}$",
    color=COLOR["Base"],
    edgecolor="black",
    linewidth=0.45,
    hatch=HATCH["Base"],
)

ax.bar(
    dual_x,
    df["Dual candidates"],
    width=bar_width,
    label=r"EUOL-Miner$_{\mathrm{Dual}}$",
    color=COLOR["Dual"],
    edgecolor="black",
    linewidth=0.45,
    hatch=HATCH["Dual"],
)

# Log scale
ax.set_yscale("log")

ax.set_ylabel("Number of candidates")
ax.set_xlabel("Dataset", labelpad=2)

ax.set_xticks(x)
ax.set_xticklabels(
    df["Dataset"],
    rotation=0,
    ha="center",
    fontstyle="normal",
    fontweight="normal"
)

for label in ax.get_xticklabels():
    label.set_fontstyle("normal")
    label.set_fontweight("normal")

ax.tick_params(axis="both", width=0.45, length=2.5, pad=2)

# Compact legend
legend = ax.legend(
    frameon=True,
    fancybox=False,
    edgecolor="black",
    ncol=1,
    loc="upper left",
    borderpad=0.25,
    handlelength=1.2,
    handletextpad=0.35,
    labelspacing=0.25,
)
legend.get_frame().set_linewidth(0.45)
legend.get_frame().set_facecolor("white")

# =========================
# Braces + labels
# =========================
ymax = df["Base candidates"].max()
ax.set_ylim(1e5, ymax * 4.2)
ax.set_xlim(-0.55, len(df) - 0.05)

for i, row in df.iterrows():
    base = row["Base candidates"]
    dual = row["Dual candidates"]
    pruned = row["Pruned candidates"]
    rate = row["Reduction rate"]

    brace_x = x[i] + 0.30

    draw_vertical_brace(
        ax,
        x=brace_x,
        y0=dual,
        y1=base,
        width=0.045,
        lw=0.65,
        color="black",
    )

    mid_y = (base * dual) ** 0.5

    # Compact annotation for one-column figure
    ax.text(
        brace_x + 0.09,
        mid_y,
        f"Pruned\n{fmt_short(pruned)}\n({rate:.1f}%)",
        ha="left",
        va="center",
        fontsize=5.6,
        linespacing=0.95,
    )

    # Base value
    ax.text(
        base_x[i],
        base * 1.12,
        fmt_short(base),
        ha="center",
        va="bottom",
        fontsize=5.4,
        rotation=90,
    )

    # Dual value
    ax.text(
        dual_x[i],
        dual * 1.12,
        fmt_short(dual),
        ha="center",
        va="bottom",
        fontsize=5.4,
        rotation=90,
    )

fig.tight_layout(pad=0.35)

fig.savefig(
    OUT_DIR / "fig7a.png",
    dpi=600,
    bbox_inches="tight",
)
fig.savefig(
    OUT_DIR / "fig7a.pdf",
    bbox_inches="tight",
)

print("Saved:")
print(OUT_DIR / "fig7a.png")
print(OUT_DIR / "fig7a.pdf")