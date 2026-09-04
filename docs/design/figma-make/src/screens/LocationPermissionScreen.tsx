interface Props {
  onAllow: () => void;
  onManual: () => void;
  onBack: () => void;
}

export default function LocationPermissionScreen({ onAllow, onManual }: Props) {
  const checks = ["현재 위치를 기반으로 경로를 안내해요", "도착·출발을 자동으로 감지해요", "근처 혼잡도와 날씨를 더 정확하게 반영해요"];
  const locks = ["실시간 경로 재계산 기능", "자동 도착·출발 처리", "더 정확한 변수 감지"];

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="flex-1 flex flex-col items-center justify-center px-6 text-center pt-8">
        <div className="w-24 h-24 rounded-3xl bg-[#EBF2FF] flex items-center justify-center mb-8">
          <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="1.8">
            <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/>
          </svg>
        </div>
        <h1 className="text-[24px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>위치 권한이 필요해요</h1>
        <p className="text-[14px] text-[#94A3B8] leading-relaxed mb-8">길픽이 제대로 작동하려면<br/>기기 위치 접근 권한을 허용해주세요</p>

        <div className="w-full bg-white rounded-2xl p-5 mb-4 text-left" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <p className="text-[12px] font-black text-[#94A3B8] uppercase tracking-wider mb-3">권한을 켜면</p>
          {checks.map((c, i) => (
            <div key={i} className="flex items-center gap-3 mb-2 last:mb-0">
              <div className="w-5 h-5 rounded-full bg-[#ECFDF5] flex items-center justify-center flex-shrink-0">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>
              </div>
              <p className="text-[13px] text-[#111827] font-medium">{c}</p>
            </div>
          ))}
        </div>

        <div className="w-full bg-[#FFF7ED] rounded-2xl p-5 text-left">
          <p className="text-[12px] font-black text-[#F97316] uppercase tracking-wider mb-3">권한 없이는</p>
          {locks.map((l, i) => (
            <div key={i} className="flex items-center gap-3 mb-2 last:mb-0">
              <div className="w-5 h-5 rounded-full bg-[#FEF3C7] flex items-center justify-center flex-shrink-0">
                <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2.5"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </div>
              <p className="text-[13px] text-[#92400E] font-medium">{l}</p>
            </div>
          ))}
        </div>
      </div>

      <div className="px-4 pb-10 pt-4 space-y-2">
        <button onClick={onAllow} className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}>
          위치 권한 허용하기
        </button>
        <button onClick={onManual} className="w-full h-[48px] rounded-2xl font-semibold text-[14px] text-[#6B7280] bg-[#F4F6FB]">
          나중에 하기
        </button>
      </div>
    </div>
  );
}
