interface Props {
  onRetry: () => void;
  onBack: () => void;
}

export default function ErrorScreen({ onRetry, onBack }: Props) {
  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="flex-1 flex flex-col items-center justify-center px-6 text-center">
        <div className="w-24 h-24 rounded-3xl bg-[#FEF2F2] flex items-center justify-center mb-8">
          <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="1.8">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/>
            <line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
        </div>

        <h1 className="text-[24px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>
          문제가 발생했어요
        </h1>
        <p className="text-[14px] text-[#94A3B8] leading-relaxed mb-8">
          경로를 업데이트하지 못했어요.<br/>기존 일정과 도착 시각은 그대로 유지됩니다.
        </p>

        <div className="w-full bg-white rounded-2xl p-5 text-left mb-4" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <p className="text-[11px] font-black text-[#94A3B8] uppercase tracking-wider mb-3">오류 정보</p>
          <div className="space-y-3">
            {[
              { label: "오류 코드", value: "ROUTE_TIMEOUT" },
              { label: "발생 시각", value: "오후 2:32" },
              { label: "마지막 동작", value: "경로 재계산" },
            ].map((row) => (
              <div key={row.label} className="flex items-center justify-between">
                <span className="text-[13px] text-[#6B7280]">{row.label}</span>
                <span className="text-[13px] font-semibold text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{row.value}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="w-full bg-[#EBF2FF] rounded-2xl px-4 py-3 flex items-center gap-2.5">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2" className="flex-shrink-0"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          <p className="text-[12px] text-[#2457C5] font-medium">인터넷 연결을 확인한 후 재시도해주세요</p>
        </div>
      </div>

      <div className="px-4 pb-10 pt-4 space-y-2">
        <button
          onClick={onRetry}
          className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white"
          style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}
        >
          다시 시도하기
        </button>
        <button onClick={onBack} className="w-full h-[48px] rounded-2xl font-semibold text-[14px] text-[#6B7280] bg-[#F4F6FB]">
          여행 진행으로 돌아가기
        </button>
      </div>
    </div>
  );
}
