import { useState } from "react";

interface Props {
  onBack: () => void;
  onAlternative: () => void;
}

type SortOrder = "시간순" | "위험순";

interface AlertItem {
  id: string;
  place: string;
  summary: string;
  details: { icon: "crowd" | "rain" | "closing"; label: string; value: string; severity: "high" | "med" | "low" }[];
  time: string;
  detectedAgo: string;
  infoNote?: string;
}

const alerts: AlertItem[] = [
  { id: "1", place: "경복궁", summary: "오늘 오후 방문이 어려울 수 있어요", details: [
    { icon: "crowd", label: "혼잡도", value: "아주 높음", severity: "high" },
    { icon: "rain", label: "강수 예보", value: "높음", severity: "med" },
    { icon: "closing", label: "운영 종료", value: "마감 30분 전 도착", severity: "low" },
  ], time: "14:00 방문 예정", detectedAgo: "8분 전 감지" },
  { id: "2", place: "창덕궁 후원", summary: "오늘 오전 방문이 어려울 수 있어요", details: [
    { icon: "crowd", label: "혼잡도", value: "경계", severity: "low" },
    { icon: "closing", label: "운영 종료", value: "경계", severity: "low" },
  ], time: "11:30 방문 예정", detectedAgo: "21분 전 감지" },
  { id: "3", place: "남산서울타워", summary: "오늘 저녁 방문이 어려울 수 있어요", details: [
    { icon: "rain", label: "강수 예보", value: "경계", severity: "low" },
    { icon: "crowd", label: "혼잡도", value: "높음", severity: "med" },
  ], time: "18:30 방문 예정", detectedAgo: "34분 전 감지", infoNote: "운영 시간 정보를 불러오지 못해 이 변수는 제외했습니다." },
];

const severityColor = (s: AlertItem["details"][0]["severity"]) => {
  if (s === "high") return "text-[#EF4444]";
  if (s === "med") return "text-[#F97316]";
  return "text-[#F59E0B]";
};

const DetailIcon = ({ type }: { type: AlertItem["details"][0]["icon"] }) => {
  if (type === "crowd") return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>;
  if (type === "rain") return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="16" y1="13" x2="16" y2="21"/><line x1="8" y1="13" x2="8" y2="21"/><line x1="12" y1="15" x2="12" y2="23"/><path d="M20 16.58A5 5 0 0 0 18 7h-1.26A8 8 0 1 0 4 15.25"/></svg>;
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>;
};

interface PropsExt extends Props { hasAlerts?: boolean; }

export default function VariableMonitorScreen({ onBack, onAlternative, hasAlerts = true }: PropsExt) {
  const [sort, setSort] = useState<SortOrder>("시간순");

  if (!hasAlerts) {
    return (
      <div className="flex flex-col h-full bg-[#F4F6FB]">
        <div className="bg-white px-5 pt-3 pb-4 flex items-center gap-3">
          <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
          </button>
          <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>변수 감지</h1>
        </div>
        <div className="flex-1 flex flex-col items-center justify-center px-6 text-center">
          <div className="w-20 h-20 rounded-3xl bg-[#ECFDF5] flex items-center justify-center mb-6">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/></svg>
          </div>
          <h2 className="text-[22px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>모든 일정이 예정대로예요</h2>
          <p className="text-[14px] text-[#94A3B8] leading-relaxed">10분마다 다시 확인하고,<br/>변수가 생기면 바로 알려드릴게요.</p>
        </div>
        <div className="px-4 pb-8">
          <button onClick={onBack} className="w-full h-[52px] rounded-2xl bg-[#F4F6FB] font-semibold text-[14px] text-[#6B7280]">여행 진행 화면으로</button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center gap-3">
        <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>변수 감지</h1>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4">
        <div className="bg-[#FFF7ED] rounded-2xl px-4 py-4 mb-4 flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#F97316] flex items-center justify-center flex-shrink-0">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          </div>
          <div>
            <h2 className="text-[16px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>방문이 어려울 수 있어요</h2>
            <p className="text-[12px] text-[#C2410C]">남은 일정 3곳에서 변수 감지</p>
          </div>
        </div>

        <div className="flex items-center justify-between mb-3 px-1">
          <span className="text-[13px] font-bold text-[#111827]">감지 {alerts.length}건</span>
          <button onClick={() => setSort(sort === "시간순" ? "위험순" : "시간순")} className="flex items-center gap-1 text-[12px] font-bold text-[#3B7BF8]">
            {sort} <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
        </div>

        <div className="space-y-3">
          {alerts.map((alert) => (
            <div key={alert.id} className="bg-white rounded-2xl p-4" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
              <div className="flex items-center gap-2 mb-1">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                <p className="text-[15px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>{alert.place}</p>
              </div>
              <p className="text-[12px] text-[#6B7280] mb-3">{alert.summary}</p>
              <div className="space-y-2 mb-3">
                {alert.details.map((d, di) => (
                  <div key={di} className="flex items-center gap-2 text-[#94A3B8]">
                    <DetailIcon type={d.icon} />
                    <span className="text-[12px]">{d.label}</span>
                    <span className={`text-[12px] font-bold ${severityColor(d.severity)}`}>{d.value}</span>
                  </div>
                ))}
              </div>
              {alert.infoNote && (
                <div className="flex items-start gap-2 bg-[#EFF6FF] rounded-xl px-3 py-2 mb-3">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2" className="flex-shrink-0 mt-0.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                  <p className="text-[11px] text-[#3B7BF8]">{alert.infoNote}</p>
                </div>
              )}
              <div className="flex items-center justify-between border-t border-[#F4F6FB] pt-3">
                <p className="text-[11px] text-[#94A3B8]">{alert.time} · {alert.detectedAgo}</p>
                <button onClick={onAlternative} className="flex items-center gap-1 text-[13px] font-bold text-[#3B7BF8]">
                  대체 장소 보기 <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M9 18l6-6-6-6"/></svg>
                </button>
              </div>
            </div>
          ))}
        </div>
        <div className="h-6" />
      </div>

      <div className="px-4 pb-8 pt-3">
        <button onClick={onBack} className="w-full h-[50px] rounded-2xl bg-[#F4F6FB] font-semibold text-[14px] text-[#6B7280]">
          여행 진행 화면으로
        </button>
      </div>
    </div>
  );
}
