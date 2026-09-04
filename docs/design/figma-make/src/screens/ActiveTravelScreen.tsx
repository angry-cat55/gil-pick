import { useState, useEffect } from "react";

interface Props {
  onSettings: () => void;
  onAlternative: () => void;
  onAddPlace: () => void;
  onRoutePreview: () => void;
  onDayRoute: () => void;
  onVariableMonitor: () => void;
  onNotifications: () => void;
}

type ModalType = "departed" | "arrived" | null;

interface ItineraryItem {
  id: string;
  name: string;
  time: string;
  status: "done" | "active" | "upcoming";
  statusLabel?: string;
  transport?: string;
  transportIcon?: "walk" | "subway" | "bus";
}

const DAY_ITINERARIES: Record<number, ItineraryItem[]> = {
  1: [
    { id: "1", name: "경복궁", time: "방문 완료", status: "done", transport: "도보 18분", transportIcon: "walk" },
    { id: "2", name: "창덕궁", time: "방문 완료", status: "done", transport: "지하철 12분", transportIcon: "subway" },
    { id: "3", name: "북촌한옥마을", time: "방문 완료", status: "done" },
  ],
  2: [
    { id: "1", name: "경복궁", time: "12:30 방문 완료", status: "done", transport: "도보 18분", transportIcon: "walk" },
    { id: "2", name: "북촌한옥마을", time: "오후 2:35 도착 예정", status: "active", statusLabel: "이동 중", transport: "지하철 12분", transportIcon: "subway" },
    { id: "3", name: "인사동거리", time: "오후 4:00 도착 예정", status: "upcoming", statusLabel: "예정", transport: "버스 24분", transportIcon: "bus" },
    { id: "4", name: "남산서울타워", time: "오후 6:30 도착 예정", status: "upcoming", statusLabel: "예정" },
  ],
  3: [
    { id: "1", name: "서울숲", time: "10:00 예정", status: "upcoming", transport: "지하철 20분", transportIcon: "subway" },
    { id: "2", name: "덕수궁", time: "12:30 예정", status: "upcoming", transport: "도보 10분", transportIcon: "walk" },
    { id: "3", name: "인사동거리", time: "14:00 예정", status: "upcoming" },
  ],
  4: [
    { id: "1", name: "남산서울타워", time: "11:00 예정", status: "upcoming", transport: "버스 30분", transportIcon: "bus" },
    { id: "2", name: "국립중앙박물관", time: "14:00 예정", status: "upcoming" },
  ],
  5: [
    { id: "1", name: "광장시장", time: "09:00 예정", status: "upcoming", transport: "지하철 20분", transportIcon: "subway" },
    { id: "2", name: "가로수길", time: "11:00 예정", status: "upcoming" },
  ],
};

const TOTAL_DAYS = 5;
const CURRENT_DAY = 2;

const TransportIcon = ({ type }: { type?: "walk" | "subway" | "bus" }) => {
  if (!type) return null;
  if (type === "walk") return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>;
  if (type === "subway") return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>;
  return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M1 12h1M22 12h1M5 12H3a2 2 0 0 0-2 2v2h18v-2a2 2 0 0 0-2-2h-2"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="18" r="2"/></svg>;
};

export default function ActiveTravelScreen({ onSettings, onAlternative, onAddPlace, onRoutePreview: _onRoutePreview, onDayRoute, onVariableMonitor, onNotifications }: Props) {
  const [modal, setModal] = useState<ModalType>("arrived");
  const [viewingDay, setViewingDay] = useState(CURRENT_DAY);
  const [toast, setToast] = useState<{ msg: string; countdown: number } | null>({
    msg: "장소가 창덕궁으로 변경되었습니다",
    countdown: 12,
  });

  const isToday = viewingDay === CURRENT_DAY;
  const itinerary = DAY_ITINERARIES[viewingDay] ?? [];

  useEffect(() => {
    if (!toast) return;
    if (toast.countdown <= 0) { setToast(null); return; }
    const t = setTimeout(() => setToast((p) => p ? { ...p, countdown: p.countdown - 1 } : null), 1000);
    return () => clearTimeout(t);
  }, [toast]);

  const dayLabels = ["5/20", "5/21", "5/22", "5/23", "5/24"];

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB] relative overflow-hidden">
      {/* Header */}
      <div className="bg-white px-5 pt-3 pb-3 flex-shrink-0">
        <div className="flex items-start justify-between mb-3">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <span className="px-2.5 py-0.5 rounded-lg bg-[#ECFDF5] text-[#10B981] text-[11px] font-black">여행 중</span>
              <span className="text-[12px] text-[#94A3B8]">{CURRENT_DAY}일차 · 1/4 완료</span>
            </div>
            <h1 className="text-[20px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>서울 자유여행</h1>
          </div>
          <div className="flex items-center gap-2 mt-1">
            <button onClick={onNotifications} className="relative w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#4B5563" strokeWidth="2">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
                <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
              </svg>
              <span className="absolute top-1.5 right-1.5 w-1.5 h-1.5 bg-[#F97316] rounded-full" />
            </button>
            <button onClick={onVariableMonitor} className="w-9 h-9 rounded-xl bg-[#FFF7ED] flex items-center justify-center">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
            </button>
            <button onClick={onSettings} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#4B5563" strokeWidth="2">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
              </svg>
            </button>
          </div>
        </div>

        {/* Day progress with day markers */}
        <div className="relative">
          {/* Track */}
          <div className="relative h-1.5 bg-[#E2E8F0] rounded-full overflow-visible mx-1">
            <div className="h-full bg-[#3B7BF8] rounded-full" style={{ width: `${((CURRENT_DAY - 1) / TOTAL_DAYS) * 100}%` }} />
          </div>
          {/* Day dots */}
          <div className="flex justify-between mt-1.5 px-0.5">
            {Array.from({ length: TOTAL_DAYS }, (_, i) => {
              const day = i + 1;
              const isPast = day < CURRENT_DAY;
              const isCurrent = day === CURRENT_DAY;
              const isViewing = day === viewingDay;
              return (
                <button key={day} onClick={() => setViewingDay(day)} className="flex flex-col items-center gap-1">
                  <div className={`w-2.5 h-2.5 rounded-full border-2 transition-all ${
                    isPast ? "bg-[#3B7BF8] border-[#3B7BF8]" :
                    isCurrent ? "bg-[#3B7BF8] border-[#3B7BF8]" :
                    "bg-white border-[#CBD5E1]"
                  } ${isViewing ? "ring-2 ring-[#3B7BF8] ring-offset-1" : ""}`} />
                  <span className={`text-[9px] font-bold transition-colors ${isViewing ? "text-[#3B7BF8]" : "text-[#CBD5E1]"}`}>{dayLabels[i]}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Viewing non-today indicator */}
        {!isToday && (
          <div className="mt-2.5 flex items-center justify-between bg-[#F4F6FB] rounded-xl px-3.5 py-2">
            <span className="text-[12px] font-semibold text-[#94A3B8]">
              {viewingDay < CURRENT_DAY ? `${viewingDay}일차 · 지난 일정` : `${viewingDay}일차 · 예정 일정`}
            </span>
            <button onClick={() => setViewingDay(CURRENT_DAY)} className="text-[12px] font-bold text-[#3B7BF8]">오늘로 돌아가기</button>
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto">
        {/* Today-only: alert banner */}
        {isToday && (
          <button
            onClick={onAlternative}
            className="mx-4 mt-4 w-[calc(100%-32px)] rounded-2xl px-4 py-3 flex items-center gap-3 active:scale-[0.99] transition-transform"
            style={{ background: "linear-gradient(135deg, #FFF7ED 0%, #FFEDD5 100%)", border: "1px solid #FED7AA" }}
          >
            <div className="w-9 h-9 rounded-xl bg-[#F97316] flex items-center justify-center flex-shrink-0">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
            </div>
            <div className="flex-1 text-left">
              <p className="text-[13px] font-bold text-[#92400E]">인사동거리 지금 매우 혼잡해요</p>
              <p className="text-[12px] text-[#B45309]">오후 4:00 도착 예정 · 5분 전 감지</p>
            </div>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2.5"><path d="M9 18l6-6-6-6"/></svg>
          </button>
        )}

        {/* Today-only: next place card */}
        {isToday && (
          <div className="mx-4 mt-3 bg-white rounded-3xl p-5" style={{ boxShadow: "0 4px 20px rgba(0,0,0,0.08)" }}>
            <p className="text-[11px] font-bold text-[#94A3B8] uppercase tracking-wider mb-1">다음 장소</p>
            <h2 className="text-[22px] font-black text-[#111827] mb-0.5" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>북촌한옥마을</h2>
            <div className="flex items-baseline gap-2 mb-1">
              <span className="text-[13px] font-semibold text-[#94A3B8]">예상 도착</span>
              <span className="text-[26px] font-black text-[#3B7BF8]" style={{ fontFamily: "Outfit, sans-serif" }}>오후 2:35</span>
              <span className="text-[13px] text-[#94A3B8]">· 12분 남았어요</span>
            </div>
            <p className="text-[13px] text-[#94A3B8] mb-3">경복궁에서 도보 18분 · 1.4km</p>
            <div className="flex items-center gap-2 bg-[#EFF6FF] rounded-xl px-3 py-2 mb-4">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2"><path d="M20 17.58A5 5 0 0 0 18 8h-1.26A8 8 0 1 0 4 16.25"/><line x1="8" y1="16" x2="8.01" y2="16"/><line x1="12" y1="18" x2="12.01" y2="18"/><line x1="16" y1="16" x2="16.01" y2="16"/></svg>
              <p className="text-[12px] text-[#3B7BF8] font-semibold flex-1">도착 시간에 비가 올 수 있어요</p>
              <span className="text-[10px] text-[#93C5FD]">기상청 3분 전</span>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setModal("arrived")} className="flex-1 h-[48px] rounded-xl font-bold text-[14px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>도착했어요</button>
              <button className="flex-1 h-[48px] rounded-xl font-semibold text-[14px] text-[#6B7280] bg-[#F4F6FB]">건너뛰기</button>
            </div>
          </div>
        )}

        {/* Map */}
        <div className="mx-4 mt-3 h-[150px] rounded-2xl overflow-hidden relative">
          <svg className="absolute inset-0 w-full h-full" viewBox="0 0 340 150" xmlns="http://www.w3.org/2000/svg">
            <defs><pattern id="mg2" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#3B7BF8" strokeWidth="0.3"/></pattern></defs>
            <rect width="340" height="150" fill="#EBF2FF"/>
            <rect width="340" height="150" fill="url(#mg2)" opacity="0.5"/>
            <path d="M 0 75 Q 90 55 170 75 Q 250 95 340 65" fill="none" stroke="white" strokeWidth="8" opacity="0.7"/>
            <path d="M 170 0 L 170 150" fill="none" stroke="white" strokeWidth="5" opacity="0.5"/>
            <path d="M 55 95 C 110 70 200 58 230 55" fill="none" stroke="#3B7BF8" strokeWidth="3" strokeDasharray="6 3"/>
            <circle cx="55" cy="95" r="9" fill="#3B7BF8"/>
            <circle cx="55" cy="95" r="16" fill="#3B7BF8" opacity="0.2"/>
            <text x="55" y="100" textAnchor="middle" fontSize="9" fill="white" fontWeight="800">현위치</text>
            <circle cx="230" cy="55" r="9" fill="#10B981"/>
            <circle cx="230" cy="55" r="16" fill="#10B981" opacity="0.2"/>
            <text x="230" y="59" textAnchor="middle" fontSize="8" fill="white" fontWeight="800">2</text>
          </svg>
          <button onClick={onDayRoute} className="absolute bottom-3 right-3 bg-white rounded-lg px-3 py-1.5 text-[12px] font-bold text-[#3B7BF8] shadow-md">
            경로 보기
          </button>
        </div>

        {/* Itinerary */}
        <div className="mx-4 mt-3 mb-3">
          <div className="flex items-center justify-between mb-2 px-1">
            <h3 className="text-[14px] font-bold text-[#111827]">
              {viewingDay}일차 일정
            </h3>
            <span className="text-[12px] text-[#94A3B8]">5월 {19 + viewingDay}일 · {itinerary.length}곳</span>
          </div>
          <div className="bg-white rounded-2xl px-4 py-3 space-y-3">
            {itinerary.map((item, i) => (
              <div key={item.id} className="flex gap-3">
                <div className="flex flex-col items-center">
                  <div className={`w-6 h-6 rounded-full flex items-center justify-center flex-shrink-0 ${
                    item.status === "done" ? "bg-[#10B981]" : item.status === "active" ? "bg-[#3B7BF8]" : "bg-[#E2E8F0]"
                  }`}>
                    {item.status === "done" ? (
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                    ) : item.status === "active" ? (
                      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="3"><path d="M9 18l6-6-6-6"/></svg>
                    ) : (
                      <span className="text-[9px] font-black text-[#94A3B8]">{i + 1}</span>
                    )}
                  </div>
                  {i < itinerary.length - 1 && (
                    <div className={`w-px h-7 mt-1 ${item.status === "done" ? "bg-[#10B981]" : "bg-[#E2E8F0]"}`} />
                  )}
                </div>
                <div className="flex-1 pb-0.5">
                  <div className="flex items-center gap-2">
                    <p className={`font-semibold text-[14px] ${item.status === "upcoming" ? "text-[#94A3B8]" : "text-[#111827]"}`}>{item.name}</p>
                    {item.statusLabel && item.status !== "done" && (
                      <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${
                        item.status === "active" ? "bg-[#EBF2FF] text-[#3B7BF8]" : "bg-[#F4F6FB] text-[#94A3B8]"
                      }`}>{item.statusLabel}</span>
                    )}
                  </div>
                  <p className={`text-[12px] mt-0.5 ${item.status === "upcoming" ? "text-[#CBD5E1]" : "text-[#94A3B8]"}`}>{item.time}</p>
                  {item.transport && (
                    <div className="flex items-center gap-1 mt-0.5 text-[#CBD5E1]">
                      <TransportIcon type={item.transportIcon} />
                      <span className="text-[11px]">{item.transport}</span>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>

        {isToday && (
          <div className="px-4 pb-4">
            <button onClick={onAddPlace} className="w-full h-[46px] rounded-xl border-2 border-dashed border-[#3B7BF8]/30 flex items-center justify-center gap-2 text-[13px] font-semibold text-[#3B7BF8] bg-[#EBF2FF]/50">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M12 5v14M5 12h14"/></svg>
              장소 추가
            </button>
          </div>
        )}
        <div className="h-4" />
      </div>

      {/* Toast */}
      {toast && (
        <div className="absolute bottom-4 left-4 right-4 z-30" style={{ animation: "slide-up 0.3s ease-out" }}>
          <div className="rounded-2xl px-4 py-3.5 flex items-center justify-between" style={{ background: "rgba(17,24,39,0.92)", backdropFilter: "blur(12px)" }}>
            <span className="text-[13px] text-white font-medium">{toast.msg}</span>
            <div className="flex items-center gap-2.5">
              <span className="text-[12px] text-white/50 font-medium">{toast.countdown}초</span>
              <button onClick={() => setToast(null)} className="text-[13px] font-black text-[#34D399]">되돌리기</button>
            </div>
          </div>
        </div>
      )}

      {/* Arrived modal — today only */}
      {isToday && modal === "arrived" && (
        <div className="absolute inset-0 z-40 flex flex-col justify-end" style={{ background: "rgba(0,0,0,0.4)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-t-[32px] px-6 pt-5 pb-8" style={{ animation: "slide-up 0.25s ease-out" }}>
            <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-5" />
            <div className="w-12 h-12 rounded-2xl bg-[#EBF2FF] flex items-center justify-center mb-4">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
            </div>
            <h3 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>북촌한옥마을에 도착하셨나요?</h3>
            <p className="text-[13px] text-[#94A3B8] mb-4">이 근처에서 6분 머무는 중 · 오후 2:33 감지</p>
            <div className="flex items-center gap-2 bg-[#ECFDF5] rounded-xl px-4 py-2.5 mb-5">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
              <span className="text-[12px] font-semibold text-[#10B981] flex-1">4분 뒤 자동으로 도착 처리돼요</span>
              <span className="text-[13px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>3:58</span>
            </div>
            <button onClick={() => setModal(null)} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>
              네, 도착했어요
            </button>
            <button onClick={() => setModal(null)} className="w-full h-[48px] rounded-2xl font-medium text-[14px] text-[#6B7280] bg-[#F4F6FB]">
              아직이에요
            </button>
          </div>
        </div>
      )}

      {isToday && modal === "departed" && (
        <div className="absolute inset-0 z-40 flex flex-col justify-end" style={{ background: "rgba(0,0,0,0.4)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-t-[32px] px-6 pt-5 pb-8">
            <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-5" />
            <h3 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>경복궁에서 출발하셨나요?</h3>
            <p className="text-[13px] text-[#94A3B8] mb-5">이 장소를 벗어나는 것으로 보여요 · 오후 12:28 감지</p>
            <button onClick={() => setModal(null)} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>
              네, 출발했어요
            </button>
            <button onClick={() => setModal(null)} className="w-full h-[48px] rounded-2xl font-medium text-[14px] text-[#6B7280] bg-[#F4F6FB]">
              아직 머무는 중이에요
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
