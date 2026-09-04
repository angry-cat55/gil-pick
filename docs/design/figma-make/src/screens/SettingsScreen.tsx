import { useState } from "react";

interface Props {
  onLogout: () => void;
  onNavigate?: (screen: string) => void;
}

export default function SettingsScreen({ onLogout }: Props) {
  const [notifs, setNotifs] = useState({ crowd: true, rain: true, closing: false });
  const [sliders, setSliders] = useState({ crowd: 70, rain: 50, closing: 30 });

  const toggle = (key: keyof typeof notifs) => setNotifs((p) => ({ ...p, [key]: !p[key] }));
  const setSlider = (key: keyof typeof sliders, v: number) => setSliders((p) => ({ ...p, [key]: v }));

  const Toggle = ({ on, onToggle }: { on: boolean; onToggle: () => void }) => (
    <button onClick={onToggle} className={`relative w-12 h-6 rounded-full transition-colors ${on ? "bg-[#3B7BF8]" : "bg-[#E2E8F0]"}`}>
      <div className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all shadow-sm ${on ? "left-[26px]" : "left-0.5"}`} />
    </button>
  );

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      {/* Header */}
      <div className="bg-white px-5 pt-10 pb-5">
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-2xl overflow-hidden bg-[#E8EDF5]">
            <img src="https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=56&h=56&fit=crop&auto=format" alt="profile" className="w-full h-full object-cover" />
          </div>
          <div>
            <p className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>김길픽</p>
            <div className="flex items-center gap-1.5 mt-0.5">
              <div className="w-4 h-4 rounded-full bg-[#FEE500] flex items-center justify-center">
                <svg width="10" height="10" viewBox="0 0 20 20" fill="#111827"><path fillRule="evenodd" d="M10 2C5.03 2 1 5.13 1 9.005c0 2.49 1.55 4.66 3.91 5.915l-1 3.68a.25.25 0 0 0 .37.275L8.2 16.79c.58.065 1 .095 1.52.095C14.97 16.885 19 13.755 19 9.005S14.97 2 10 2z"/></svg>
              </div>
              <span className="text-[12px] text-[#94A3B8] font-medium">카카오 연동</span>
            </div>
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {/* Notifications */}
        <div className="mt-3 bg-white">
          <p className="px-5 pt-4 pb-2 text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">알림 설정</p>
          {[
            { key: "crowd" as const, label: "혼잡도 알림", sub: "방문지 혼잡이 기준 이상일 때" },
            { key: "rain" as const, label: "강수 예보 알림", sub: "이동 중 비가 올 가능성이 높을 때" },
            { key: "closing" as const, label: "운영 종료 알림", sub: "마감 시간 30분 전 도착 예정일 때" },
          ].map((item, i) => (
            <div key={item.key} className={`flex items-center px-5 py-4 ${i < 2 ? "border-b border-[#F4F6FB]" : ""}`}>
              <div className="flex-1">
                <p className="text-[14px] font-semibold text-[#111827]">{item.label}</p>
                <p className="text-[12px] text-[#94A3B8]">{item.sub}</p>
              </div>
              <Toggle on={notifs[item.key]} onToggle={() => toggle(item.key)} />
            </div>
          ))}
        </div>

        {/* Thresholds */}
        <div className="mt-3 bg-white">
          <p className="px-5 pt-4 pb-2 text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">감지 기준</p>
          {[
            { key: "crowd" as const, label: "혼잡도 기준", unit: "%" },
            { key: "rain" as const, label: "강수 확률 기준", unit: "%" },
            { key: "closing" as const, label: "마감 여유 시간", unit: "분" },
          ].map((item, i) => (
            <div key={item.key} className={`px-5 py-4 ${i < 2 ? "border-b border-[#F4F6FB]" : ""}`}>
              <div className="flex items-center justify-between mb-2">
                <p className="text-[14px] font-semibold text-[#111827]">{item.label}</p>
                <span className="text-[15px] font-black text-[#3B7BF8]" style={{ fontFamily: "Outfit, sans-serif" }}>{sliders[item.key]}{item.unit}</span>
              </div>
              <input
                type="range" min={0} max={100} value={sliders[item.key]}
                onChange={(e) => setSlider(item.key, Number(e.target.value))}
                className="w-full h-1.5 rounded-full appearance-none bg-[#E2E8F0]"
                style={{ accentColor: "#3B7BF8" }}
              />
            </div>
          ))}
        </div>

        {/* App info */}
        <div className="mt-3 bg-white">
          <p className="px-5 pt-4 pb-2 text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">앱 정보</p>
          {[
            { label: "버전", value: "1.0.0" },
            { label: "개인정보처리방침" },
            { label: "이용약관" },
          ].map((item, i) => (
            <button key={item.label} className={`w-full flex items-center justify-between px-5 py-4 ${i < 2 ? "border-b border-[#F4F6FB]" : ""}`}>
              <span className="text-[14px] font-medium text-[#111827]">{item.label}</span>
              {item.value ? (
                <span className="text-[13px] text-[#94A3B8]">{item.value}</span>
              ) : (
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2"><path d="M9 18l6-6-6-6"/></svg>
              )}
            </button>
          ))}
        </div>

        {/* Reset + Logout */}
        <div className="mt-3 bg-white px-5 py-4">
          <button className="w-full h-[48px] rounded-xl bg-[#F4F6FB] font-semibold text-[14px] text-[#6B7280] mb-3">
            설정 초기화
          </button>
          <button onClick={onLogout} className="w-full h-[48px] rounded-xl bg-[#FEF2F2] font-bold text-[14px] text-[#EF4444]">
            로그아웃
          </button>
        </div>
        <div className="h-8" />
      </div>
    </div>
  );
}
