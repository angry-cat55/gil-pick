interface Props {
  onLogin: () => void;
}

export default function LoginScreen({ onLogin }: Props) {
  return (
    <div className="flex flex-col h-full" style={{ background: "linear-gradient(160deg, #0B1120 0%, #0E1A3A 55%, #0F2050 100%)" }}>
      {/* Brand area */}
      <div className="flex-1 flex flex-col items-center justify-center px-8 pt-12 pb-6">
        <svg viewBox="0 0 300 200" fill="none" xmlns="http://www.w3.org/2000/svg" className="w-full max-w-[300px] mb-10">
          <defs>
            <pattern id="lg1" width="24" height="24" patternUnits="userSpaceOnUse">
              <path d="M 24 0 L 0 0 0 24" fill="none" stroke="#3B7BF8" strokeWidth="0.3" opacity="0.35"/>
            </pattern>
          </defs>
          <rect width="300" height="200" fill="url(#lg1)"/>
          <path d="M 30 160 C 80 140 130 85 185 65" stroke="#3B7BF8" strokeWidth="2.5" strokeDasharray="6 3" opacity="0.45"/>
          <path d="M 30 160 C 70 130 150 105 240 55" stroke="#F97316" strokeWidth="2" strokeDasharray="6 3" opacity="0.55"/>
          <circle cx="30" cy="160" r="7" fill="#3B7BF8" opacity="0.9"/>
          <circle cx="30" cy="160" r="14" fill="#3B7BF8" opacity="0.12"/>
          <circle cx="115" cy="110" r="4" fill="#94A3B8" opacity="0.6"/>
          <circle cx="185" cy="65" r="7" fill="#F97316" opacity="0.95"/>
          <circle cx="185" cy="65" r="16" fill="#F97316" opacity="0.12"/>
          <circle cx="240" cy="55" r="5" fill="#3B7BF8" opacity="0.7"/>
          <rect x="192" y="48" width="54" height="16" rx="8" fill="#F97316" opacity="0.9"/>
          <text x="219" y="59" textAnchor="middle" fontSize="8" fill="white" fontWeight="700">혼잡 감지</text>
          <rect x="248" y="38" width="48" height="16" rx="8" fill="#3B7BF8" opacity="0.9"/>
          <text x="272" y="49" textAnchor="middle" fontSize="8" fill="white" fontWeight="700">경로 변경</text>
        </svg>

        <div className="text-center mb-6">
          <div className="flex items-center justify-center gap-3 mb-4">
            <div className="w-12 h-12 rounded-[14px] bg-[#3B7BF8] flex items-center justify-center" style={{ boxShadow: "0 8px 24px rgba(59,123,248,0.4)" }}>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5">
                <polygon points="3 11 22 2 13 21 11 13 3 11"/>
              </svg>
            </div>
            <span className="text-[36px] font-black text-white" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif", letterSpacing: "-0.5px" }}>길픽</span>
          </div>
          <p className="text-[15px] text-[#8BA3C7] font-medium leading-relaxed">
            실시간 혼잡·날씨·운영시간을 감지해<br/>최적의 경로를 다시 안내합니다
          </p>
        </div>

        <div className="flex gap-2 flex-wrap justify-center mt-2">
          {["혼잡도 감지", "날씨 예보", "자동 재경로"].map((f) => (
            <span key={f} className="px-3.5 py-1.5 rounded-full text-[12px] font-semibold border" style={{ borderColor: "rgba(59,123,248,0.3)", color: "#8BA3C7", background: "rgba(59,123,248,0.08)" }}>
              {f}
            </span>
          ))}
        </div>
      </div>

      {/* Bottom card */}
      <div className="bg-white rounded-t-[36px] px-6 pt-8 pb-10" style={{ boxShadow: "0 -20px 60px rgba(0,0,0,0.3)" }}>
        <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-6" />
        <p className="text-[13px] font-medium text-[#94A3B8] text-center mb-4">SNS 계정으로 간편하게 시작하기</p>
        <button
          onClick={onLogin}
          className="w-full h-[56px] rounded-2xl flex items-center justify-center gap-3 font-bold text-[15px] text-[#111827] transition-all active:scale-[0.98]"
          style={{ backgroundColor: "#FEE500", boxShadow: "0 4px 20px rgba(254,229,0,0.35)" }}
        >
          <svg width="22" height="22" viewBox="0 0 40 40" fill="#111827">
            <path fillRule="evenodd" clipRule="evenodd" d="M20 4C10.06 4 2 10.27 2 18.01c0 4.97 3.1 9.32 7.82 11.83l-2 7.36a.5.5 0 0 0 .74.55l8.4-5.57c.98.13 1.99.19 3.04.19C29.94 32.37 38 26.1 38 18.36S29.94 4 20 4z"/>
          </svg>
          카카오로 시작하기
        </button>
        <p className="text-[11px] text-[#CBD5E1] text-center mt-4 leading-relaxed">
          시작하면 이용약관 및 개인정보처리방침에 동의한 것으로 간주됩니다
        </p>
      </div>
    </div>
  );
}
