import { useState, useEffect } from "react";

interface Props {
  onBack: () => void;
  onStartTravel: () => void;
  onGoToActive: () => void;
  onSelectPlace: (name: string) => void;
  onEditSchedule: () => void;
  onEditTrip: () => void;
}

interface Place {
  name: string;
  time: string;
  duration: string;
  transport?: { type: string; duration: string; icon: "walk" | "subway" | "bus" };
}

interface Day {
  date: string;
  dayNum: number;
  places: Place[];
}

const days: Day[] = [
  { date: "8월 12일", dayNum: 1, places: [
    { name: "경복궁", time: "10:00", duration: "1시간 30분", transport: { type: "지하철", duration: "25분", icon: "subway" } },
    { name: "창덕궁", time: "11:45", duration: "1시간", transport: { type: "도보", duration: "15분", icon: "walk" } },
    { name: "북촌한옥마을", time: "13:00", duration: "1시간 30분" },
  ]},
  { date: "8월 13일", dayNum: 2, places: [
    { name: "서울숲", time: "10:00", duration: "2시간", transport: { type: "지하철", duration: "20분", icon: "subway" } },
    { name: "덕수궁", time: "12:30", duration: "1시간", transport: { type: "도보", duration: "10분", icon: "walk" } },
    { name: "인사동거리", time: "14:00", duration: "1시간" },
  ]},
  { date: "8월 14일", dayNum: 3, places: [
    { name: "남산서울타워", time: "11:00", duration: "2시간 30분", transport: { type: "버스", duration: "30분", icon: "bus" } },
    { name: "국립중앙박물관", time: "14:00", duration: "2시간" },
  ]},
  { date: "8월 15일", dayNum: 4, places: [
    { name: "명동거리", time: "10:00", duration: "1시간", transport: { type: "지하철", duration: "12분", icon: "subway" } },
    { name: "롯데월드타워", time: "11:30", duration: "1시간 30분" },
  ]},
  { date: "8월 16일", dayNum: 5, places: [
    { name: "광장시장", time: "09:00", duration: "1시간 30분", transport: { type: "지하철", duration: "20분", icon: "subway" } },
    { name: "가로수길", time: "11:00", duration: "2시간" },
  ]},
];

const TransportIcon = ({ type }: { type: "walk" | "subway" | "bus" }) => {
  if (type === "walk") return <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>;
  if (type === "subway") return <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>;
  return <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M1 12h1M22 12h1M5 12H3a2 2 0 0 0-2 2v2h18v-2a2 2 0 0 0-2-2h-2"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="18" r="2"/></svg>;
};

export default function TripDetailScreen({ onBack, onStartTravel, onGoToActive: _onGoToActive, onSelectPlace, onEditSchedule, onEditTrip }: Props) {
  const [secondsLeft, setSecondsLeft] = useState(5);
  const [started, setStarted] = useState(false);
  const [showMenu, setShowMenu] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  // Simulate: button becomes active after 5 seconds (demo)
  useEffect(() => {
    if (secondsLeft <= 0) return;
    const t = setTimeout(() => setSecondsLeft((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [secondsLeft]);

  const isActive = secondsLeft === 0;

  const handleTravelBtn = () => {
    setStarted(true);
    onStartTravel();
  };

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      {/* Hero */}
      <div className="relative h-[180px] flex-shrink-0">
        <img src="https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=390&h=180&fit=crop&auto=format" alt="서울" className="w-full h-full object-cover bg-[#CBD5E1]" />
        <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0.1) 50%, rgba(0,0,0,0.6) 100%)" }} />
        <button onClick={onBack} className="absolute top-3 left-5 w-9 h-9 rounded-full bg-black/30 backdrop-blur-sm flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <div className="absolute top-3 right-5 flex gap-2">
          <div className="relative">
            <button onClick={() => setShowMenu((v) => !v)} className="w-9 h-9 rounded-full bg-black/30 backdrop-blur-sm flex items-center justify-center">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2"><circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/></svg>
            </button>
            {showMenu && (
              <div className="absolute top-11 right-0 w-[168px] bg-white rounded-2xl overflow-hidden z-[60]" style={{ boxShadow: "0 8px 32px rgba(0,0,0,0.18)" }}>
                <button
                  onClick={() => { setShowMenu(false); onEditTrip(); }}
                  className="w-full flex items-center gap-3 px-4 py-3.5 text-left active:bg-[#F4F6FB] transition-colors"
                >
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                  <span className="text-[14px] font-semibold text-[#111827]">여행 편집</span>
                </button>
                <div className="h-px bg-[#F4F6FB]" />
                <button
                  onClick={() => { setShowMenu(false); setShowDeleteConfirm(true); }}
                  className="w-full flex items-center gap-3 px-4 py-3.5 text-left active:bg-[#FEF2F2] transition-colors"
                >
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>
                  <span className="text-[14px] font-semibold text-[#EF4444]">여행 삭제</span>
                </button>
              </div>
            )}
          </div>
        </div>
        <div className="absolute bottom-4 left-5">
          <p className="text-white/80 text-[12px] font-medium mb-0.5">서울특별시</p>
          <h1 className="text-white text-[22px] font-black" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>서울 자유여행</h1>
          <p className="text-white/70 text-[13px] mt-0.5">2025. 8. 12 – 8. 16 · 4박 5일</p>
        </div>
      </div>

      {/* Stats + CTA */}
      <div className="bg-white px-5 py-4">
        <div className="flex items-center gap-4 mb-4">
          {[{ v: "12곳", l: "총 방문지" }, { v: "5일", l: "여행 기간" }, { v: "3h 20m", l: "총 이동" }].map((s) => (
            <div key={s.l} className="flex-1 text-center">
              <p className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{s.v}</p>
              <p className="text-[11px] text-[#94A3B8] font-medium">{s.l}</p>
            </div>
          ))}
        </div>
        {!isActive && (
          <div className="flex items-center gap-2 bg-[#F4F6FB] rounded-xl px-3.5 py-2.5 mb-3">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2" className="flex-shrink-0"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
            <span className="text-[12px] text-[#94A3B8] font-medium flex-1">오늘은 여행 날짜가 아닙니다</span>
            <span className="text-[12px] font-black text-[#3B7BF8]" style={{ fontFamily: "Outfit, sans-serif" }}>{secondsLeft}초</span>
          </div>
        )}
        <button
          onClick={handleTravelBtn}
          disabled={!isActive}
          className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-3 transition-all disabled:opacity-40"
          style={isActive ? { background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" } : { background: "#CBD5E1" }}
        >
          {started ? "여행 진행 화면으로" : "오늘 여행 시작"}
        </button>
        <button onClick={onEditSchedule} className="w-full flex items-center justify-center gap-1.5 py-2 text-[13px] font-semibold text-[#6B7280]">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          일정 편집
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2.5"><path d="M9 18l6-6-6-6"/></svg>
        </button>
      </div>

      {/* Itinerary */}
      <div className="flex-1 overflow-y-auto">
        {days.map((day) => (
          <div key={day.date} className="mb-1">
            <div className="flex items-center justify-between px-5 py-3 bg-[#F4F6FB]">
              <div className="flex items-center gap-2">
                <span className="w-6 h-6 rounded-lg bg-[#3B7BF8] flex items-center justify-center text-[10px] font-black text-white">{day.dayNum}</span>
                <span className="text-[14px] font-bold text-[#111827]">{day.date}</span>
              </div>
              <span className="text-[12px] text-[#94A3B8]">{day.places.length}곳</span>
            </div>
            <div className="bg-white">
              {day.places.map((place, i) => (
                <div key={place.name}>
                  <button onClick={() => onSelectPlace(place.name)} className="w-full flex items-center gap-3 px-5 py-3.5 text-left active:bg-[#F4F6FB]">
                    <div className="w-6 h-6 rounded-full bg-[#EBF2FF] flex items-center justify-center flex-shrink-0">
                      <span className="text-[10px] font-black text-[#3B7BF8]">{i + 1}</span>
                    </div>
                    <div className="flex-1">
                      <p className="font-semibold text-[14px] text-[#111827]">{place.name}</p>
                      <p className="text-[12px] text-[#94A3B8]">{place.time} · {place.duration}</p>
                    </div>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2"><path d="M9 18l6-6-6-6"/></svg>
                  </button>
                  {place.transport && i < day.places.length - 1 && (
                    <div className="flex items-center gap-1.5 px-5 py-1.5 ml-11">
                      <div className="w-px h-4 bg-[#E2E8F0]" />
                      <div className="flex items-center gap-1 text-[#94A3B8]">
                        <TransportIcon type={place.transport.icon} />
                        <span className="text-[11px]">{place.transport.type} {place.transport.duration}</span>
                      </div>
                    </div>
                  )}
                  {i < day.places.length - 1 && <div className="h-px bg-[#F4F6FB] ml-14" />}
                </div>
              ))}
            </div>
          </div>
        ))}
        <div className="h-8" />
      </div>

      {showMenu && <div className="absolute inset-0 z-40" onClick={() => setShowMenu(false)} />}

      {/* Delete confirm */}
      {showDeleteConfirm && (
        <div className="absolute inset-0 z-50 flex items-center justify-center px-6" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-3xl p-6 w-full shadow-2xl">
            <div className="w-12 h-12 rounded-2xl bg-[#FEF2F2] flex items-center justify-center mb-4">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>
            </div>
            <h2 className="text-[20px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>여행을 삭제할까요?</h2>
            <p className="text-[13px] text-[#6B7280] mb-6">삭제한 여행은 복구할 수 없습니다</p>
            <button onClick={() => setShowDeleteConfirm(false)} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>취소</button>
            <button onClick={onBack} className="w-full text-[14px] font-semibold text-[#EF4444] py-2">삭제하기</button>
          </div>
        </div>
      )}
    </div>
  );
}
