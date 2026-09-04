interface Props {
  onBack: () => void;
  onApprove: () => void;
  onOtherCandidates: () => void;
}

export default function RoutePreviewScreen({ onBack, onApprove, onOtherCandidates }: Props) {
  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center gap-3">
        <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>경로 비교</h1>
      </div>

      {/* Map */}
      <div className="mx-4 mt-3 h-[190px] rounded-2xl overflow-hidden relative">
        <svg className="w-full h-full" viewBox="0 0 340 190" xmlns="http://www.w3.org/2000/svg">
          <defs><pattern id="rmg" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#3B7BF8" strokeWidth="0.3"/></pattern></defs>
          <rect width="340" height="190" fill="#EBF2FF"/>
          <rect width="340" height="190" fill="url(#rmg)" opacity="0.5"/>
          <path d="M 0 95 Q 90 75 170 95 Q 250 115 340 85" fill="none" stroke="white" strokeWidth="8" opacity="0.65"/>
          <path d="M 50 150 C 110 120 180 100 280 55" stroke="#CBD5E1" strokeWidth="3" strokeDasharray="8 4"/>
          <path d="M 50 150 C 90 130 200 115 280 55" stroke="#3B7BF8" strokeWidth="3"/>
          <circle cx="50" cy="150" r="10" fill="#3B7BF8"/>
          <text x="50" y="155" textAnchor="middle" fontSize="9" fill="white" fontWeight="800">현재</text>
          <circle cx="280" cy="55" r="10" fill="#F97316"/>
          <text x="280" y="59" textAnchor="middle" fontSize="9" fill="white" fontWeight="800">창덕궁</text>
        </svg>
        <div className="absolute top-3 right-3 flex gap-2">
          <div className="flex items-center gap-1.5 bg-white/90 rounded-lg px-2.5 py-1.5">
            <div className="w-4 h-px bg-[#CBD5E1]" style={{ borderTop: "2px dashed #CBD5E1" }} />
            <span className="text-[10px] font-semibold text-[#94A3B8]">기존</span>
          </div>
          <div className="flex items-center gap-1.5 bg-white/90 rounded-lg px-2.5 py-1.5">
            <div className="w-4 h-0.5 bg-[#3B7BF8]" />
            <span className="text-[10px] font-bold text-[#3B7BF8]">변경</span>
          </div>
        </div>
      </div>

      {/* Change summary */}
      <div className="bg-white mx-4 mt-3 rounded-2xl overflow-hidden" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
        <div className="px-5 py-4 border-b border-[#F4F6FB]">
          <div className="flex items-center gap-2 mb-1">
            <span className="px-2.5 py-1 rounded-lg bg-[#FFF7ED] text-[#F97316] text-[11px] font-black">경로 변경</span>
          </div>
          <p className="text-[16px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>인사동거리 → 창덕궁</p>
          <p className="text-[13px] text-[#94A3B8] mt-0.5">오후 4:00 방문 예정 · 혼잡 회피</p>
        </div>
        {[
          { label: "이동 시간", before: "24분", after: "18분", better: true },
          { label: "이동 거리", before: "5.4km", after: "0.8km", better: true },
          { label: "도착 예정", before: "16:24", after: "16:18", better: true },
          { label: "마감 시간", before: "21:00", after: "18:00", better: false },
        ].map((row) => (
          <div key={row.label} className="flex items-center px-5 py-3 border-b border-[#F4F6FB] last:border-0">
            <span className="text-[13px] text-[#94A3B8] w-20">{row.label}</span>
            <span className="text-[13px] text-[#CBD5E1] line-through mr-3">{row.before}</span>
            <span className={`text-[14px] font-bold ${row.better ? "text-[#10B981]" : "text-[#F97316]"}`}>{row.after}</span>
            {row.better && <svg className="ml-1.5" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>}
            {!row.better && <svg className="ml-1.5" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/></svg>}
          </div>
        ))}
      </div>

      <div className="flex-1" />
      <div className="px-4 pb-8 pt-4 space-y-2">
        <button onClick={onApprove} className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}>
          변경 승인
        </button>
        <button onClick={onOtherCandidates} className="w-full h-[50px] rounded-2xl font-semibold text-[14px] text-[#6B7280] bg-[#F4F6FB]">
          다른 후보 보기
        </button>
      </div>
    </div>
  );
}
