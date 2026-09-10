import { useState } from "react";

interface Props {
  onLogout: () => void;
  onNavigate?: (screen: string) => void;
}

type NotifState = "idle" | "loading" | "saving" | "error";

export default function SettingsScreen({ onLogout }: Props) {
  const [suggestion, setSuggestion] = useState(true);
  const [notifState, setNotifState] = useState<NotifState>("idle");

  const handleToggle = () => {
    if (notifState !== "idle") return;
    setSuggestion((p) => !p);
  };

  const Toggle = ({ on, disabled }: { on: boolean; disabled: boolean }) => (
    <button
      onClick={handleToggle}
      disabled={disabled}
      aria-pressed={on}
      className={`relative w-12 h-6 rounded-full transition-colors flex-shrink-0 disabled:opacity-50 ${on ? "bg-[#3B7BF8]" : "bg-[#E2E8F0]"}`}
    >
      <div className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all shadow-sm ${on ? "left-[26px]" : "left-0.5"}`} />
    </button>
  );

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      {/* ── Header (unchanged) ── */}
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

        {/* ── Demo toggle ── */}
        <div className="px-4 pt-3 flex gap-1.5">
          {(["idle", "loading", "saving", "error"] as NotifState[]).map((s) => {
            const labels: Record<NotifState, string> = { idle: "기본", loading: "불러오는 중", saving: "저장 중", error: "실패" };
            return (
              <button
                key={s}
                onClick={() => setNotifState(s)}
                className={`flex-1 py-1.5 rounded-lg text-[10px] font-bold transition-colors ${notifState === s ? "bg-[#3B7BF8] text-white" : "bg-[#F4F6FB] text-[#94A3B8]"}`}
              >
                {labels[s]}
              </button>
            );
          })}
        </div>

        {/* ── 알림 설정 ── */}
        <div className="mt-3 bg-white">
          <p className="px-5 pt-4 pb-2 text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">알림 설정</p>

          {/* loading */}
          {notifState === "loading" && (
            <div className="flex items-center px-5 py-4 min-h-[72px]">
              <div className="flex-1 mr-4">
                <p className="text-[14px] font-semibold text-[#111827]">장소 변경 제안 알림</p>
                <p className="text-[12px] text-[#94A3B8] mt-0.5 leading-relaxed">일정에 변수가 생기면 대체 장소를 제안합니다. 도착·출발 확인 알림은 계속 받습니다.</p>
              </div>
              <div className="w-12 h-6 rounded-full bg-[#E2E8F0] flex items-center justify-center flex-shrink-0">
                <svg className="animate-spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2.5">
                  <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
                </svg>
              </div>
            </div>
          )}

          {/* saving */}
          {notifState === "saving" && (
            <div className="flex items-center px-5 py-4 min-h-[72px]">
              <div className="flex-1 mr-4">
                <div className="flex items-center gap-2 mb-0.5">
                  <p className="text-[14px] font-semibold text-[#111827]">장소 변경 제안 알림</p>
                  <span className="text-[10px] font-bold text-[#94A3B8] bg-[#F4F6FB] px-2 py-0.5 rounded-md">저장 중</span>
                </div>
                <p className="text-[12px] text-[#94A3B8] leading-relaxed">일정에 변수가 생기면 대체 장소를 제안합니다. 도착·출발 확인 알림은 계속 받습니다.</p>
              </div>
              <Toggle on={suggestion} disabled={true} />
            </div>
          )}

          {/* error */}
          {notifState === "error" && (
            <div className="px-5 py-4">
              <div className="flex items-center mb-3">
                <div className="flex-1 mr-4">
                  <p className="text-[14px] font-semibold text-[#111827]">장소 변경 제안 알림</p>
                  <p className="text-[12px] text-[#94A3B8] mt-0.5 leading-relaxed">일정에 변수가 생기면 대체 장소를 제안합니다. 도착·출발 확인 알림은 계속 받습니다.</p>
                </div>
                <Toggle on={suggestion} disabled={true} />
              </div>
              <div className="flex items-center gap-3 bg-[#FFF7ED] rounded-xl px-4 py-3" style={{ border: "1px solid #FED7AA" }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2" className="flex-shrink-0"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                <p className="flex-1 text-[12px] font-semibold text-[#92400E]">설정을 불러오지 못했어요</p>
                <button
                  onClick={() => setNotifState("idle")}
                  className="h-[32px] px-3 rounded-lg bg-[#F97316] text-white text-[12px] font-bold flex-shrink-0"
                >
                  다시 시도
                </button>
              </div>
            </div>
          )}

          {/* idle (default) */}
          {notifState === "idle" && (
            <div className="flex items-center px-5 py-4 min-h-[72px]">
              <div className="flex-1 mr-4">
                <p className="text-[14px] font-semibold text-[#111827]">장소 변경 제안 알림</p>
                <p className="text-[12px] text-[#94A3B8] mt-0.5 leading-relaxed">일정에 변수가 생기면 대체 장소를 제안합니다. 도착·출발 확인 알림은 계속 받습니다.</p>
              </div>
              <Toggle on={suggestion} disabled={false} />
            </div>
          )}
        </div>

        {/* ── 앱 정보 (unchanged) ── */}
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

        {/* ── 로그아웃 (unchanged) ── */}
        <div className="mt-3 bg-white px-5 py-4">
          <button onClick={onLogout} className="w-full h-[48px] rounded-xl bg-[#FEF2F2] font-bold text-[14px] text-[#EF4444]">
            로그아웃
          </button>
        </div>
        <div className="h-8" />
      </div>
    </div>
  );
}
