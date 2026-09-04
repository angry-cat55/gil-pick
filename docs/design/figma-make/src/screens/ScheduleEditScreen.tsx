import { useState } from "react";
import React from "react";

interface Props {
  onBack: () => void;
  onSave: () => void;
  onAddPlace: () => void;
  isNew?: boolean;
}

type PlaceStatus = "예정" | "완료" | "건너뜀";
type Transport = "도보" | "대중교통" | "자동차";

interface SchedulePlace {
  id: string;
  name: string;
  time: string;
  duration: number;
  status: PlaceStatus;
  transport?: { label: string; icon: "walk" | "bus" | "subway"; type: Transport };
}

const initialPlaces: SchedulePlace[] = [
  { id: "1", name: "경복궁", time: "09:00", duration: 90, status: "예정", transport: { label: "도보 12분 · 0.8km", icon: "walk", type: "도보" } },
  { id: "2", name: "북촌한옥마을", time: "10:45", duration: 60, status: "예정", transport: { label: "버스 18분 · 4.2km", icon: "bus", type: "대중교통" } },
  { id: "3", name: "인사동 쌈지길", time: "13:00", duration: 90, status: "완료", transport: { label: "도보 8분 · 0.6km", icon: "walk", type: "도보" } },
  { id: "4", name: "창덕궁", time: "15:00", duration: 60, status: "건너뜀" },
];

const days = [{ n: 21, dow: "목" }, { n: 22, dow: "금" }, { n: 23, dow: "토" }, { n: 24, dow: "일" }];

const transportOptions: { type: Transport; icon: React.ReactElement; label: string; detail: string; icon2: "walk" | "bus" | "subway" }[] = [
  { type: "도보", icon2: "walk", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>, label: "도보", detail: "약 18분 · 1.2km" },
  { type: "대중교통", icon2: "subway", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>, label: "대중교통", detail: "약 9분 · 2정거장" },
  { type: "자동차", icon2: "bus", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 17H3a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v9a2 2 0 0 1-2 2h-2"/><circle cx="7.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/></svg>, label: "자동차", detail: "약 5분 · 주차 가능" },
];

const TransportIcon = ({ type }: { type: "walk" | "bus" | "subway" }) => {
  if (type === "walk") return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>;
  if (type === "bus") return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="3" y="3" width="18" height="14" rx="2"/><path d="M3 11h18M8 19v-2m8 2v-2"/></svg>;
  return <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>;
};

export default function ScheduleEditScreen({ onBack, onSave, onAddPlace, isNew = false }: Props) {
  const [selectedDay, setSelectedDay] = useState(1);
  const [places, setPlaces] = useState<SchedulePlace[]>(initialPlaces);
  const [showCancel, setShowCancel] = useState(false);
  const [durationModal, setDurationModal] = useState<{ id: string; name: string } | null>(null);
  const [tempDuration, setTempDuration] = useState(90);
  const [transportModal, setTransportModal] = useState<{ id: string; name: string } | null>(null);
  const [tempTransport, setTempTransport] = useState<Transport>("도보");
  const [tempTransportDuration, setTempTransportDuration] = useState(90);

  const removePlace = (id: string) => setPlaces(places.filter((p) => p.id !== id));

  const openDuration = (p: SchedulePlace) => {
    setTempDuration(p.duration);
    setDurationModal({ id: p.id, name: p.name });
  };

  const applyDuration = () => {
    setPlaces(places.map((p) => p.id === durationModal?.id ? { ...p, duration: tempDuration } : p));
    setDurationModal(null);
  };

  const openTransport = (p: SchedulePlace) => {
    setTempTransport(p.transport?.type ?? "도보");
    setTempTransportDuration(90);
    setTransportModal({ id: p.id, name: p.name });
  };

  const applyTransport = () => {
    const opt = transportOptions.find((o) => o.type === tempTransport);
    setPlaces(places.map((p) => p.id === transportModal?.id ? {
      ...p, transport: { label: opt?.detail ?? "", icon: opt?.icon2 ?? "walk", type: tempTransport },
    } : p));
    setTransportModal(null);
  };

  const adjustDur = (delta: number) => { const n = tempDuration + delta; if (n >= 30 && n <= 360) setTempDuration(n); };
  const adjustTransDur = (delta: number) => { const n = tempTransportDuration + delta; if (n >= 5 && n <= 120) setTempTransportDuration(n); };

  const getTimeRange = (time: string, dur: number) => {
    const [h, m] = time.split(":").map(Number);
    const end = h * 60 + m + dur;
    return `${time} – ${String(Math.floor(end / 60) % 24).padStart(2, "0")}:${String(end % 60).padStart(2, "0")}`;
  };


  return (
    <div className="flex flex-col h-full bg-[#F4F6FB] relative">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button onClick={() => setShowCancel(true)} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
          </button>
          <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>일정 편집</h1>
        </div>
        <button className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-3.5"/></svg>
        </button>
      </div>

      {/* Day tabs */}
      <div className="bg-white px-5 pb-4 border-b border-[#F4F6FB]">
        <div className="flex gap-2">
          {days.map((d, i) => (
            <button key={d.n} onClick={() => setSelectedDay(i)}
              className={`flex flex-col items-center px-4 py-2.5 rounded-2xl transition-colors ${selectedDay === i ? "bg-[#111827]" : "bg-[#F4F6FB]"}`}>
              <span className={`text-[18px] font-black ${selectedDay === i ? "text-white" : "text-[#111827]"}`} style={{ fontFamily: "Outfit, sans-serif" }}>{d.n}</span>
              <span className={`text-[11px] font-semibold ${selectedDay === i ? "text-white/70" : "text-[#94A3B8]"}`}>{d.dow}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4">
        <div className="flex items-center justify-between mb-3 px-1">
          <p className="text-[14px] font-bold text-[#111827]">5월 {days[selectedDay].n}일 방문 장소</p>
          <p className="text-[12px] text-[#94A3B8]">2일차 · {places.length}곳</p>
        </div>


        <div className="bg-white rounded-2xl overflow-hidden" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          {places.map((place, i) => (
            <div key={place.id}>
              <div className="flex items-start gap-3 px-4 py-4">
                <div className={`w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5 ${
                  isNew
                    ? "bg-[#3B7BF8]"
                    : place.status === "완료" ? "bg-[#10B981]" : place.status === "건너뜀" ? "bg-[#CBD5E1]" : "bg-[#3B7BF8]"
                }`}>
                  {!isNew && place.status === "완료" ? (
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                  ) : (
                    <span className="text-[11px] font-black text-white">{i + 1}</span>
                  )}
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-0.5">
                    <p className="font-bold text-[15px] text-[#111827]">{place.name}</p>
                  </div>
                  <p className="text-[12px] text-[#94A3B8]">{place.time} · {place.duration}분</p>
                  {place.transport && (
                    <div className="flex items-center gap-1.5 mt-1.5 text-[#CBD5E1]">
                      <TransportIcon type={place.transport.icon} />
                      <span className="text-[12px]">{place.transport.label}</span>
                      <button onClick={() => openTransport(place)} className="ml-1 text-[12px] font-bold text-[#3B7BF8]">변경</button>
                    </div>
                  )}
                </div>
                <button onClick={() => removePlace(place.id)} className="w-8 h-8 rounded-lg bg-[#FEF2F2] flex items-center justify-center mt-0.5 flex-shrink-0">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>
                </button>
              </div>
              {i < places.length - 1 && <div className="h-px bg-[#F4F6FB] mx-4" />}
            </div>
          ))}
        </div>

        <button onClick={onAddPlace} className="w-full h-[50px] mt-3 border-2 border-dashed border-[#3B7BF8]/25 rounded-2xl flex items-center justify-center gap-2 text-[13px] font-bold text-[#3B7BF8] bg-[#EBF2FF]/40">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M12 5v14M5 12h14"/></svg>
          장소 추가
        </button>
        <p className="text-[11px] text-[#94A3B8] text-center mt-3">변경하면 남은 일정의 도착 시각이 다시 계산됩니다</p>
        <div className="h-6" />
      </div>

      <div className="px-4 pb-8 pt-3">
        <button onClick={onSave} className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}>
          저장
        </button>
      </div>

      {/* Duration modal */}
      {durationModal && (
        <div className="absolute inset-0 z-50 flex items-center justify-center px-5" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-3xl p-6 w-full shadow-2xl">
            <h2 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>{durationModal.name} 체류 시간</h2>
            <p className="text-[13px] text-[#94A3B8] mb-5">변경 시 이후 일정 도착 시각이 함께 조정됩니다</p>
            <div className="flex items-center justify-between bg-[#F4F6FB] rounded-2xl px-4 py-4 mb-4">
              <button onClick={() => adjustDur(-30)} className="w-11 h-11 rounded-full bg-white flex items-center justify-center text-xl font-black text-[#111827]" style={{ boxShadow: "0 2px 8px rgba(0,0,0,0.1)" }}>−</button>
              <div className="text-center">
                <p className="text-[32px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{tempDuration}<span className="text-[18px] ml-1">분</span></p>
                <p className="text-[12px] text-[#94A3B8]">
                  {(() => { const p = places.find(x => x.id === durationModal.id); return p ? getTimeRange(p.time, tempDuration) : ""; })()}
                </p>
              </div>
              <button onClick={() => adjustDur(30)} className="w-11 h-11 rounded-full flex items-center justify-center text-xl font-black text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 12px rgba(59,123,248,0.3)" }}>+</button>
            </div>
            <div className="flex gap-2 mb-4">
              {[60, 90, 120].map((v) => (
                <button key={v} onClick={() => setTempDuration(v)}
                  className={`flex-1 py-2.5 rounded-xl text-[13px] font-bold transition-colors ${tempDuration === v ? "bg-[#111827] text-white" : "bg-[#F4F6FB] text-[#6B7280]"}`}>
                  {v}분
                </button>
              ))}
            </div>
            <div className="flex gap-3">
              <button onClick={() => setDurationModal(null)} className="flex-1 h-[50px] rounded-xl text-[14px] font-semibold text-[#6B7280] bg-[#F4F6FB]">취소</button>
              <button onClick={applyDuration} className="flex-[2] h-[50px] rounded-xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>적용</button>
            </div>
          </div>
        </div>
      )}

      {/* Transport modal */}
      {transportModal && (
        <div className="absolute inset-0 z-50 flex flex-col justify-end" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-t-[32px] px-6 pt-5 pb-8" style={{ animation: "slide-up 0.25s ease-out" }}>
            <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-5" />
            <h2 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>이동 수단 변경</h2>
            <p className="text-[13px] text-[#94A3B8] mb-5">{transportModal.name}까지 어떻게 이동하시겠어요?</p>
            <div className="space-y-2 mb-5">
              {transportOptions.map((opt) => (
                <button key={opt.type} onClick={() => setTempTransport(opt.type)}
                  className={`w-full flex items-center gap-3 px-4 py-3.5 rounded-2xl border-2 transition-all ${tempTransport === opt.type ? "border-[#3B7BF8] bg-[#EBF2FF]" : "border-[#E2E8F0] bg-white"}`}>
                  <span className={tempTransport === opt.type ? "text-[#3B7BF8]" : "text-[#94A3B8]"}>{opt.icon}</span>
                  <div className="flex-1 text-left">
                    <p className={`font-bold text-[14px] ${tempTransport === opt.type ? "text-[#3B7BF8]" : "text-[#111827]"}`}>{opt.label}</p>
                    <p className="text-[12px] text-[#94A3B8]">{opt.detail}</p>
                  </div>
                  {tempTransport === opt.type && <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>}
                </button>
              ))}
            </div>
            <p className="text-[14px] font-bold text-[#111827] mb-3">체류 시간</p>
            <div className="flex items-center justify-between bg-[#F4F6FB] rounded-2xl px-4 py-3 mb-5">
              <button onClick={() => adjustTransDur(-5)} className="w-10 h-10 rounded-full bg-white flex items-center justify-center text-xl font-black text-[#111827]" style={{ boxShadow: "0 2px 6px rgba(0,0,0,0.08)" }}>−</button>
              <span className="text-[22px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{tempTransportDuration}분</span>
              <button onClick={() => adjustTransDur(5)} className="w-10 h-10 rounded-full flex items-center justify-center text-white text-xl font-black" style={{ background: "linear-gradient(135deg, #3B7BF8, #2457C5)" }}>+</button>
            </div>
            <div className="flex gap-3">
              <button onClick={() => setTransportModal(null)} className="flex-1 h-[50px] rounded-2xl text-[14px] font-semibold text-[#6B7280] bg-[#F4F6FB]">취소</button>
              <button onClick={applyTransport} className="flex-[2] h-[50px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8, #2457C5)" }}>적용</button>
            </div>
          </div>
        </div>
      )}

      {/* Cancel dialog */}
      {showCancel && (
        <div className="absolute inset-0 z-50 flex items-center justify-center px-6" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-3xl p-6 w-full shadow-2xl">
            <h2 className="text-[20px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>편집을 취소할까요?</h2>
            <p className="text-[13px] text-[#6B7280] mb-6">저장하지 않은 변경 사항은 사라집니다</p>
            <button onClick={() => setShowCancel(false)} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>계속 편집</button>
            <button onClick={onBack} className="w-full text-[14px] font-semibold text-[#EF4444] py-2">취소하고 나가기</button>
          </div>
        </div>
      )}
    </div>
  );
}
