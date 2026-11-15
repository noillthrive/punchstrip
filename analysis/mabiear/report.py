"""분석 JSON 들을 묶어 한국어 마크다운 보고서를 만든다(report)."""
from __future__ import annotations

from typing import Any

CAUTION = "유사도는 '원곡과 닮았나'이고 '좋게 들리나'가 아니다."

SCHEMAS = {
    "align": "mabiear.align/1",
    "chroma": "mabiear.chroma/1",
    "onset": "mabiear.onset/1",
    "balance": "mabiear.balance/1",
    "ab": "mabiear.ab/1",
    "b_chroma": "mabiear.chroma/1",
    "b_onset": "mabiear.onset/1",
    "b_balance": "mabiear.balance/1",
    "richness": "mabiear.richness/1",
}


def _f(v: Any, nd: int = 2) -> str:
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:.{nd}f}"
    return str(v)


def _notes(v: list | None) -> str:
    return " ".join(v) if v else "-"


def _label(m: int, off: int) -> str:
    """표의 마디 칸. 오프셋이 있으면 B 번호를 괄호로: '27 (B 29)'."""
    return str(m) if not off else f"{m} (B {m + off})"


def _bal_text(b: list | None) -> str:
    b = b or []
    if not b:
        return "-"
    return "; ".join(b[:4]) + (f" 외 {len(b) - 4}" if len(b) > 4 else "")


def _range(w: dict) -> str:
    a = w.get("from_measure", w.get("from"))
    b = w.get("to_measure", w.get("to"))
    if a is None and b is None:
        return "범위 없음"
    return f"{a}~{b}마디" if a != b else f"{a}마디"


def _range_warnings(ws: list | None) -> list[str]:
    """마디 번호가 없는 경고(구간 경고 lead_margin_low 등)를 범위로 적은 문장."""
    out = []
    for w in ws or []:
        if not isinstance(w, dict) or isinstance(w.get("measure"), int):
            continue
        if w.get("type") == "lead_margin_low":
            out.append(f"리드 여유 낮음: {w.get('section', '-')}({_range(w)}) {_f(w.get('lead_margin_db'), 1)}dB "
                       f"< {_f((w.get('band') or [None])[0], 1)}dB, 가장 센 경쟁 {w.get('main_competitor', '-')}")
        else:
            out.append(f"{w.get('type', '경고')}: " + (w.get("text") or _range(w)))
    return out


def build(title: str, docs: dict[str, dict], paths: dict[str, str], top_n: int = 5,
          b_offset: int = 0) -> tuple[str, dict]:
    """docs 키: align, chroma, onset, balance, ab, richness(A 쪽과 A/B) + b_chroma, b_onset, b_balance(B 쪽, 선택).

    마디별 표의 기준은 A 마디 번호다. B 쪽 결과의 마디 x 는 A 마디 x - b_offset 줄에 놓는다.
    """
    al, ch, on, ba, ab = (docs.get(k) for k in ("align", "chroma", "onset", "balance", "ab"))
    ri = docs.get("richness")
    bch, bon, bba = (docs.get(k) for k in ("b_chroma", "b_onset", "b_balance"))
    has_b = bool(bch or bon or bba)
    off = b_offset
    A = "A " if has_b else ""  # B 쪽 결과가 있으면 열 이름에 A/B 를 붙인다
    L: list[str] = [f"# {title}", "", f"> **주의: {CAUTION}** 이 보고서의 수치는 원곡 녹음과의 거리일 뿐이며, "
                    "듣기 좋은지는 사람의 귀로 판단한다.", ""]
    L.append("## 입력")
    for k, p in paths.items():
        L.append(f"- {k}: `{p}`")
    if off:
        L.append(f"- 마디 짝: A의 n마디 ↔ B의 n{off:+d}마디(b_measure_offset {off}). 표의 마디 번호는 A 기준, "
                 "괄호 안이 B 번호")
    L.append("")
    summary: dict[str, Any] = {}
    if off:
        summary["b_measure_offset"] = off
    if al:
        c = al.get("confidence") or {}
        L += ["## 정렬", "",
              f"- 렌더 0초 = 원곡 **{_f(al.get('offset_sec'), 3)}초**, 첫 마디 시작 = 원곡 {_f(al.get('first_measure_orig_sec'), 3)}초",
              f"- 템포 비율(원곡 시간 ÷ 렌더 시간) **{_f(al.get('tempo_ratio'), 4)}**",
              f"- 정렬 신뢰도 **{c.get('level', '-')}** (경로 비용 {_f(c.get('mean_path_cost'), 3)}, "
              f"아무렇게나 맞춘 값 {_f(c.get('baseline_cost'), 3)}). {c.get('text', '')}"]
        md = al.get("mapping_decision") or {}
        if md.get("text"):
            L.append(f"- 마디·박 경계 옮기기: {md['text']}")
        for w in c.get("warnings") or []:
            L.append(f"- 경고: {w}")
        L.append("")
        summary["offset_sec"] = al.get("offset_sec")
        if al.get("mapping_used"):
            summary["mapping_used"] = al.get("mapping_used")
        summary["tempo_ratio"] = al.get("tempo_ratio")
        summary["align_confidence"] = c.get("level")

    # 마디별 표(키 = A 마디 번호)
    rows: dict[int, dict] = {}

    def row(m: int) -> dict:
        return rows.setdefault(m, {"measure": m})

    def fill(chd: dict | None, ond: dict | None, bad: dict | None, pre: str, shift: int) -> None:
        if chd:
            for x in chd.get("measures", []):
                row(x["measure"] - shift).update({pre + "sim": x.get("score"), pre + "missing": x.get("missing"),
                                                   pre + "added": x.get("added")})
        if ond:
            for x in ond.get("measures", []):
                row(x["measure"] - shift)[pre + "onset_f"] = x.get("f")
        if bad:
            for w in bad.get("warnings", []):
                if not isinstance(w, dict) or not isinstance(w.get("measure"), int):
                    continue  # 구간 경고(lead_margin_low 등)는 밸런스 절에 범위로 적는다
                rr = row(w["measure"] - shift)
                if w.get("type") == "buried":
                    used = w.get("rel_mix_db_used", w.get("rel_mix_db"))  # 이전 JSON 은 rel_mix_db 뿐
                    t = f"묻힘 {w.get('part')}({_f(used, 1)}dB)"
                elif w.get("type") == "low_crowding":
                    t = f"저음뭉침 {w.get('beat')}박"
                else:
                    t = str(w.get("type") or "경고")
                rr.setdefault(pre + "bal", []).append(t)
            for x in bad.get("measures", []):
                row(x["measure"] - shift)

    fill(ch, on, ba, "", 0)
    fill(bch, bon, bba, "b_", off)
    if ri:
        for x in ri.get("measures", []):
            row(x["measure"]).update(rich=f"{x.get('render_onsets')}/{x.get('orig_onsets')}"
                                          + (f" ({_f(x.get('onset_ratio'), 2)})" if x.get("onset_ratio") is not None
                                             else ""))
    if ab:
        for x in ab.get("measures", []):
            row(x["measure"]).update(ab=x.get("score"), ab_db=x.get("rms_diff_db"))
    if rows:
        cols = ["마디"]
        if ch:
            cols += [f"{A}유사도", f"{A}빠진 음", f"{A}더한 음"]
        if bch:
            cols += ["B 유사도", "B 빠진 음", "B 더한 음"]
        if on:
            cols += [f"{A}온셋 F"]
        if bon:
            cols += ["B 온셋 F"]
        if ri:
            cols += [f"{A}음 시작 렌더/원곡"]
        if ba:
            cols += [f"{A}밸런스 경고"]
        if bba:
            cols += ["B 밸런스 경고"]
        if ab:
            cols += ["A/B 유사도", "B−A dB"]
        L += ["## 마디별", "", "| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
        for m in sorted(rows):
            x = rows[m]
            cells = [_label(m, off)]
            if ch:
                cells += [_f(x.get("sim"), 3), _notes(x.get("missing")), _notes(x.get("added"))]
            if bch:
                cells += [_f(x.get("b_sim"), 3), _notes(x.get("b_missing")), _notes(x.get("b_added"))]
            if on:
                cells += [_f(x.get("onset_f"), 2)]
            if bon:
                cells += [_f(x.get("b_onset_f"), 2)]
            if ri:
                cells += [x.get("rich") or "-"]
            if ba:
                cells += [_bal_text(x.get("bal"))]
            if bba:
                cells += [_bal_text(x.get("b_bal"))]
            if ab:
                cells += [_f(x.get("ab"), 3), _f(x.get("ab_db"), 1)]
            L.append("| " + " | ".join(cells) + " |")
        L.append("")
        cp = (ch or bch or {}).get("params", {})
        notes = []
        if ch or bch:
            notes.append("유사도: 박 단위 12음 크로마의 코사인(0~1, 1=같은 음 구성). 빠진 음: 원곡에서는 그 마디 최대 세기의 "
                         f"{_f(cp.get('missing_hi'), 2)} 이상인데 렌더에서는 {_f(cp.get('missing_lo'), 2)} 미만인 음이름"
                         "(더한 음은 반대).")
        if on or bon:
            notes.append("온셋 F: 음 시작 시각이 허용 오차 안에서 맞은 정도(0~1).")
        if ri:
            notes.append("음 시작 렌더/원곡: 마디 안 온셋 수(괄호는 비율).")
        if notes:
            L.append(" ".join(notes))
        if has_b:
            L.append("")
            L.append("A·B 열은 각 렌더를 원곡과 따로 비교한 값이고, A/B 열은 두 렌더끼리 비교한 값이다.")
        L.append("")

    def own(m: int, side: str) -> str:
        """하위 마디 목록의 마디 표기. side='a': A 번호(B 번호), side='b': B 번호(A 번호)."""
        if not off:
            return f"{m}마디"
        return f"{m}마디(B {m + off}마디)" if side == "a" else f"B {m}마디(A {m - off}마디)"

    def who(side: str) -> str:
        return "" if not has_b else ("A: " if side == "a" else "B: ")

    L.append("## 하위 마디")
    L.append("")
    for doc, side, pre in ((ch, "a", ""), (bch, "b", "b_")):
        if not doc:
            continue
        L.append(f"{who(side)}원곡과 덜 닮은 마디(크로마, 평균 {_f(doc.get('mean_score'), 3)}):")
        for i, x in enumerate(doc.get("bottom", [])[:top_n], start=1):
            L.append(f"{i}. {own(x['measure'], side)} — 유사도 {_f(x.get('score'), 3)}, "
                     f"빠진 음 {_notes(x.get('missing'))}, 더한 음 {_notes(x.get('added'))}")
        summary[pre + "chroma_mean"] = doc.get("mean_score")
        summary[pre + "chroma_bottom"] = [x["measure"] for x in doc.get("bottom", [])[:top_n]]
        L.append("")
    for doc, side, pre in ((on, "a", ""), (bon, "b", "b_")):
        if not doc:
            continue
        L.append(f"{who(side)}리듬이 덜 맞는 마디(온셋 F, 평균 {_f(doc.get('mean_f'), 3)}):")
        for i, x in enumerate(doc.get("bottom", [])[:top_n], start=1):
            L.append(f"{i}. {own(x['measure'], side)} — F {_f(x.get('f'), 2)} (렌더 온셋 {x.get('render_onsets')}개, "
                     f"원곡 {x.get('orig_onsets')}개)")
        summary[pre + "onset_mean_f"] = doc.get("mean_f")
        L.append("")
    if ab:
        L.append(f"두 렌더가 가장 다른 마디(A/B 크로마, 평균 {_f(ab.get('mean_score'), 3)}):")
        for i, x in enumerate(ab.get("bottom", [])[:top_n], start=1):
            L.append(f"{i}. {own(x['measure'], 'a')} — 유사도 {_f(x.get('score'), 3)}, "
                     f"B−A {_f(x.get('rms_diff_db'), 1)}dB")
        sk = ab.get("skipped_measures") or []
        if sk:
            L.append(f"(B에 짝이 없어 건너뛴 A 마디 {len(sk)}개: " + ", ".join(str(v) for v in sk[:10])
                     + (" …" if len(sk) > 10 else "") + ")")
        summary["ab_mean"] = ab.get("mean_score")
        summary["ab_skipped_count"] = len(sk)
        L.append("")
    if ri:
        sm = ri.get("summary", {})
        pr = ri.get("params", {})
        bands = pr.get("bands_hz", {})
        lo = (bands.get("low") or [0, 250])[1]
        hi = (bands.get("high") or [2000])[0]
        rel = sm.get("band_db_rel_mean") or {}
        L += ["## 풍성함" if not has_b else "## 풍성함 (A)", "",
              f"- 음 시작 수 렌더/원곡: 마디 평균 {_f(sm.get('mean_onset_ratio'), 2)}, "
              f"합 {sm.get('render_onsets')}/{sm.get('orig_onsets')}개"]
        L.append(f"- 대역 세기 차(렌더−원곡, 전체 세기 차 {_f(sm.get('level_offset_db'), 1)}dB를 뺀 값): "
                 f"저(<{_f(lo, 0)}Hz) {_f(rel.get('low'), 1)}dB, 중 {_f(rel.get('mid'), 1)}dB, "
                 f"고(≥{_f(hi, 0)}Hz) {_f(rel.get('high'), 1)}dB")
        L += ["", "가장 빈 마디(음 시작 비율 낮은 순):"]
        for i, x in enumerate((ri.get("emptiest") or [])[:top_n], start=1):
            br = x.get("band_db_rel") or {}
            L.append(f"{i}. {own(x['measure'], 'a')} — 음 시작 {x.get('render_onsets')}/{x.get('orig_onsets')}개"
                     f"(비율 {_f(x.get('onset_ratio'), 2)}), 대역 모양 차 저 {_f(br.get('low'), 1)} / 중 "
                     f"{_f(br.get('mid'), 1)} / 고 {_f(br.get('high'), 1)}dB")
        L.append("")
        L.append("음 시작 비율은 원곡 녹음(드럼·보컬·마스터링 포함)과 견준 값이다. 원곡 쪽 온셋이 덜 잡혀 1을 넘기도 하므로 "
                 "절대값보다 마디 사이 순위와 버전 사이 변화를 본다.")
        summary["richness_mean_onset_ratio"] = sm.get("mean_onset_ratio")
        summary["richness_emptiest"] = [x["measure"] for x in (ri.get("emptiest") or [])[:top_n]]
        L.append("")
    for doc, side, pre in ((ba, "a", ""), (bba, "b", "b_")):
        if not doc:
            continue
        cnt = doc.get("counts", {})
        head = "## 밸런스" if not has_b else ("## 밸런스 (A)" if side == "a" else "## 밸런스 (B)")
        pr = doc.get("params", {})
        if pr.get("buried_mode", "fixed") == "relative":
            rule = (f"소리 내는 파트 N개일 때 믹스보다 10·log10(N)+{_f(pr.get('buried_margin_db'), 0)}dB 이상 작으면 묻힘, "
                    f"저음 뭉침은 음높이 있는 파트 {pr.get('low_min_parts', 3)}개 이상")
        else:
            rule = f"믹스보다 {_f(pr.get('buried_db'), 0)}dB 이상 작으면 묻힘"
        wt = doc.get("weighting_used") or pr.get("weighting")
        if wt == "k":
            rule += ", 묻힘은 K-가중 소리 크기로 비교"
        elif wt == "rms":
            rule += ", 묻힘은 RMS로 비교"
        L += [head, "", f"- 묻힘 경고 {cnt.get('buried', 0)}건, 저음 뭉침 경고 {cnt.get('low_crowding', 0)}건 "
              f"(기준: {rule})"]
        for p, n in sorted((cnt.get("buried_by_part") or {}).items(), key=lambda kv: -kv[1]):
            L.append(f"- {p}: 묻힘 {n}개 마디")
        lm = doc.get("lead_margin") or {}
        if lm.get("sections"):
            band = lm.get("margin_band") or [None, None]
            L.append(f"- 리드 여유({', '.join(lm.get('lead_part') or [])}, 기준 {_f(band[0], 1)}~{_f(band[1], 1)}dB): "
                     + ", ".join(f"{x.get('name')}({_range(x)}) {_f(x.get('lead_margin_db'), 1)}"
                                 for x in lm["sections"][:16]))
        for w in _range_warnings(doc.get("warnings")):
            L.append(f"- {w}")
        L.append("")
        summary[pre + "balance_range_warnings"] = len(_range_warnings(doc.get("warnings")))
        summary[pre + "balance_counts"] = cnt
    if not (ch or on or ab or bch or bon):
        L.append("(크로마·온셋·A/B 결과가 없어 하위 마디 목록이 없다)")
        L.append("")
    return "\n".join(L).rstrip() + "\n", summary
