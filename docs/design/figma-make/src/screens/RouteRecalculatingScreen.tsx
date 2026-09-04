import { useState, useEffect } from "react";

interface Props {
  onBack: () => void;
}

type StepStatus = "done" | "active" | "pending";

export default function RouteRecalculatingScreen({ onBack }: Props) {
  const [step, setStep] = useState(1);

  useEffect(() => {
    const t = setTimeout(() => setStep(0), 2800);
    return () => clearTimeout(t);
  }, []);

  const steps: { label: string; status: StepStatus }[] = [
    { label: "변경된 일정 확인", status: step >= 1 ? "done" : "pending" },
    { label: "최적 경로 계산 중", status: step === 1 ? "active" : step === 0 ? "done" : "pending" },
    { label: "도착 시각 업데이트", status: step === 0 ? "done" : "pending" },
  ];

  const done = step === 0;

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center gap-3">
        <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>경로 재생성</h1>
      </div>

      <div className="flex-1 flex flex-col items-center justify-center px-6 text-center">
        {/* Spinner */}
        <div className="relative w-28 h-28 mb-8">
          <svg className="w-full h-full" viewBox="0 0 112 112">
            <circle cx="56" cy="56" r="46" fill="none" stroke="#E2E8F0" strokeWidth="7"/>
            <circle
              cx="56" cy="56" r="46"
              fill="none"
              stroke={done ? "#10B981" : "#3B7BF8"}
              strokeWidth="7"
              strokeLinecap="round"
              strokeDasharray={done ? "289 0" : "216 73"}
              strokeDashoffset="72.3"
              style={{
                transition: "stroke-dasharray 0.8s ease, stroke 0.4s ease",
                transformOrigin: "center",
                animation: !done ? "spin-cw 1.2s linear infinite" : "none"
              }}
            />
          </svg>
          {done && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="w-12 h-12 rounded-2xl bg-[#10B981] flex items-center justify-center">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>
              </div>
            </div>
          )}
          {!done && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="w-8 h-8 rounded-xl bg-[#EBF2FF] flex items-center justify-center">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2"><polygon points="3 11 22 2 13 21 11 13 3 11"/></svg>
              </div>
            </div>
          )}
        </div>

        <h2 className="text-[22px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>
          {done ? "경로 재생성 완료!" : "경로를 다시 계산하고 있습니다"}
        </h2>
        <p className="text-[14px] text-[#94A3B8] leading-relaxed mb-8">
          {done
            ? "창덕궁으로 변경한 일정에 맞춰\n경로와 도착 시각이 업데이트됐어요."
            : "창덕궁으로 변경한 일정에 맞춰\n남은 경로와 도착 시각을 갱신하고 있어요."}
        </p>

        {/* Steps */}
        <div className="bg-white rounded-2xl px-5 py-5 w-full" style={{ boxShadow: "0 2px 12px rgba(0,0,0,0.06)" }}>
          <p className="text-[11px] font-black text-[#94A3B8] uppercase tracking-wider mb-4 text-left">진행 단계</p>
          <div className="space-y-4">
            {steps.map((s, i) => (
              <div key={i} className="flex items-center gap-3">
                <div className={`w-7 h-7 rounded-xl flex items-center justify-center flex-shrink-0 transition-colors ${
                  s.status === "done" ? "bg-[#10B981]" :
                  s.status === "active" ? "bg-[#EBF2FF]" :
                  "bg-[#F4F6FB]"
                }`}>
                  {s.status === "done" ? (
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                  ) : s.status === "active" ? (
                    <div className="w-4 h-4 rounded-full border-2 border-[#3B7BF8] border-t-transparent" style={{ animation: "spin-cw 0.8s linear infinite" }} />
                  ) : (
                    <div className="w-3 h-3 rounded-full bg-[#CBD5E1]" />
                  )}
                </div>
                <span className={`text-[14px] font-semibold flex-1 text-left ${s.status === "pending" ? "text-[#CBD5E1]" : "text-[#111827]"}`}>{s.label}</span>
                {s.status === "done" && <span className="text-[12px] font-bold text-[#10B981]">완료</span>}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="px-4 pb-8 pt-4">
        <p className="text-[11px] text-[#CBD5E1] text-center mb-3">계산에 실패해도 기존 일정은 그대로 유지됩니다</p>
        <button onClick={onBack} className="w-full h-[52px] rounded-2xl bg-[#F4F6FB] font-semibold text-[14px] text-[#6B7280]">
          여행 진행 화면으로 돌아가기
        </button>
      </div>
    </div>
  );
}
