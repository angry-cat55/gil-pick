import { useState } from "react";

interface Trip {
  id: string;
  title: string;
  city: string;
  dateRange: string;
  nights: string;
  dday: string;
  places: number;
  image: string;
  status: "active" | "upcoming" | "completed";
  skipped?: number;
}

const trips: Trip[] = [
  { id: "1", title: "서울 자유여행", city: "서울특별시", dateRange: "5월 21일 – 5월 25일", nights: "4박 5일", dday: "진행 중", places: 12, image: "https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=160&h=120&fit=crop&auto=format", status: "active" },
  { id: "2", title: "북촌·인사동 탐방", city: "서울특별시", dateRange: "6월 1일 – 6월 5일", nights: "4박 5일", dday: "D-7", places: 10, image: "https://images.unsplash.com/photo-1598939404966-7c5e8e3e6b39?w=160&h=120&fit=crop&auto=format", status: "upcoming" },
  { id: "3", title: "남산 단기 여행", city: "서울특별시", dateRange: "7월 20일 – 7월 24일", nights: "4박 5일", dday: "D-56", places: 8, image: "https://images.unsplash.com/photo-1538485399081-7191377e8241?w=160&h=120&fit=crop&auto=format", status: "upcoming" },
  { id: "4", title: "한강 나들이", city: "서울특별시", dateRange: "2024. 11. 3 – 11. 10", nights: "7박 8일", dday: "", places: 14, image: "https://images.unsplash.com/photo-1561731216-c3a4d99437d5?w=160&h=120&fit=crop&auto=format", status: "completed", skipped: 1 },
  { id: "5", title: "서울숲 힐링 여행", city: "서울특별시", dateRange: "2024. 9. 14 – 9. 18", nights: "4박 5일", dday: "", places: 9, image: "https://images.unsplash.com/photo-1542038784456-1ea8e935640e?w=160&h=120&fit=crop&auto=format", status: "completed" },
];

interface Props {
  onSelectTrip: () => void;
  onCreateTrip: () => void;
  onNotifications: () => void;
}

type FilterTab = "전체" | "예정" | "여행 중" | "완료";

export default function MyTripsScreen({ onSelectTrip, onCreateTrip, onNotifications }: Props) {
  const [activeTab, setActiveTab] = useState<FilterTab>("전체");
  const [query, setQuery] = useState("");

  const filterTabs: FilterTab[] = ["전체", "예정", "여행 중", "완료"];

  const filtered = trips.filter((t) => {
    const matchQ = !query || t.title.includes(query);
    if (activeTab === "전체") return matchQ;
    if (activeTab === "예정") return t.status === "upcoming" && matchQ;
    if (activeTab === "여행 중") return t.status === "active" && matchQ;
    if (activeTab === "완료") return t.status === "completed" && matchQ;
    return matchQ;
  });

  const activeTrips = filtered.filter((t) => t.status === "active");
  const upcomingTrips = filtered.filter((t) => t.status === "upcoming");
  const completedTrips = filtered.filter((t) => t.status === "completed");

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      {/* Header */}
      <div className="bg-white px-5 pt-10 pb-4">
        <div className="flex items-center justify-between mb-5">
          <div>
            <p className="text-[12px] font-semibold text-[#3B7BF8] mb-1">MY TRIPS</p>
            <h1 className="text-[26px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif", letterSpacing: "-0.5px" }}>내 여행</h1>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={onNotifications} className="relative w-10 h-10 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4B5563" strokeWidth="2">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
                <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
              </svg>
              <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-[#F97316] rounded-full" />
            </button>
          </div>
        </div>

        <div className="flex items-center gap-2 bg-[#F4F6FB] rounded-xl px-3.5 h-[44px] mb-4">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
          <input
            type="text"
            placeholder="여행 이름으로 검색"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="flex-1 bg-transparent text-[14px] text-[#111827] placeholder-[#94A3B8] outline-none"
          />
        </div>

        <div className="flex gap-2">
          {filterTabs.map((tab) => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-4 py-2 rounded-xl text-[13px] font-semibold transition-all ${
                activeTab === tab ? "bg-[#111827] text-white" : "bg-[#F4F6FB] text-[#6B7280]"
              }`}
            >
              {tab}
            </button>
          ))}
        </div>
      </div>

      {/* Empty state */}
      {filtered.length === 0 && trips.length === 0 && (
        <div className="flex-1 flex flex-col items-center justify-center px-6 text-center">
          <div className="w-20 h-20 rounded-3xl bg-[#F4F6FB] flex items-center justify-center mb-6">
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="1.6">
              <path d="M3 3h5l2 3H3z"/><path d="M21 3H9l2 3h10z"/><path d="M3 3v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V3"/>
            </svg>
          </div>
          <h2 className="text-[18px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>아직 만든 여행이 없어요</h2>
          <p className="text-[14px] text-[#94A3B8] leading-relaxed mb-7">여행을 만들면 날짜별 일정과<br/>이동 경로를 한 번에 정리할 수 있어요</p>
          <button onClick={onCreateTrip} className="h-[52px] px-8 rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}>
            첫 여행 만들기
          </button>
        </div>
      )}

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-6" style={{ display: trips.length === 0 ? "none" : undefined }}>
        {/* Active */}
        {activeTrips.length > 0 && (
          <section>
            <div className="flex items-center gap-2 mb-3 px-1">
              <div className="w-2 h-2 rounded-full bg-[#10B981] animate-pulse" />
              <span className="text-[12px] font-bold text-[#6B7280] uppercase tracking-wider">진행 중</span>
            </div>
            {activeTrips.map((trip) => (
              <button key={trip.id} onClick={onSelectTrip} className="w-full text-left mb-3 active:scale-[0.99] transition-transform">
                <div className="bg-white rounded-2xl overflow-hidden" style={{ boxShadow: "0 2px 12px rgba(59,123,248,0.12), 0 0 0 2px #3B7BF8" }}>
                  <div className="relative">
                    <img src={trip.image} alt={trip.title} className="w-full h-[140px] object-cover bg-[#E8EDF5]" />
                    <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, transparent 40%, rgba(0,0,0,0.6) 100%)" }} />
                    <div className="absolute bottom-3 left-4 right-4 flex items-end justify-between">
                      <div>
                        <p className="text-white font-black text-[19px]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>{trip.title}</p>
                        <p className="text-white/70 text-[12px]">{trip.city}</p>
                      </div>
                      <span className="px-2.5 py-1 rounded-lg bg-[#10B981] text-white text-[11px] font-bold">여행 중</span>
                    </div>
                  </div>
                  <div className="px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-3 text-[13px] text-[#6B7280]">
                      <span>{trip.dateRange}</span>
                      <span className="w-1 h-1 rounded-full bg-[#D1D5DB]" />
                      <span>{trip.nights}</span>
                    </div>
                    <span className="text-[12px] font-bold text-[#3B7BF8]">장소 {trip.places}곳</span>
                  </div>
                </div>
              </button>
            ))}
          </section>
        )}

        {/* Upcoming */}
        {upcomingTrips.length > 0 && (
          <section>
            <div className="flex items-center gap-2 mb-3 px-1">
              <div className="w-2 h-2 rounded-full bg-[#3B7BF8]" />
              <span className="text-[12px] font-bold text-[#6B7280] uppercase tracking-wider">다가오는 여행</span>
            </div>
            <div className="space-y-2">
              {upcomingTrips.map((trip) => (
                <button key={trip.id} onClick={onSelectTrip} className="w-full text-left active:scale-[0.99] transition-transform">
                  <div className="bg-white rounded-2xl p-4 flex items-center gap-4" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
                    <img src={trip.image} alt={trip.title} className="w-[64px] h-[64px] rounded-xl object-cover bg-[#E8EDF5] flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-0.5">
                        <p className="font-bold text-[15px] text-[#111827] truncate">{trip.title}</p>
                        <span className="flex-shrink-0 px-2 py-0.5 rounded-md bg-[#EBF2FF] text-[#3B7BF8] text-[11px] font-bold">{trip.dday}</span>
                      </div>
                      <p className="text-[13px] text-[#6B7280]">{trip.dateRange}</p>
                      <p className="text-[12px] text-[#94A3B8] mt-0.5">{trip.nights} · {trip.places}곳</p>
                    </div>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2">
                      <path d="M9 18l6-6-6-6"/>
                    </svg>
                  </div>
                </button>
              ))}
            </div>
          </section>
        )}

        {/* Completed */}
        {completedTrips.length > 0 && (
          <section>
            <div className="flex items-center gap-2 mb-3 px-1">
              <div className="w-2 h-2 rounded-full bg-[#CBD5E1]" />
              <span className="text-[12px] font-bold text-[#6B7280] uppercase tracking-wider">지난 여행</span>
            </div>
            <div className="space-y-2">
              {completedTrips.map((trip) => (
                <button key={trip.id} onClick={onSelectTrip} className="w-full text-left active:scale-[0.99] transition-transform">
                  <div className="bg-white rounded-2xl p-4 flex items-center gap-4 opacity-75" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.05)" }}>
                    <img src={trip.image} alt={trip.title} className="w-[56px] h-[56px] rounded-xl object-cover bg-[#E8EDF5] flex-shrink-0 grayscale" />
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-[14px] text-[#6B7280] truncate">{trip.title}</p>
                      <p className="text-[12px] text-[#94A3B8]">{trip.dateRange}</p>
                      <p className="text-[12px] text-[#94A3B8] mt-0.5">
                        {trip.places}곳 방문{trip.skipped ? ` · ${trip.skipped}곳 건너뜀` : ""}
                      </p>
                    </div>
                    <span className="px-2 py-0.5 rounded-md bg-[#F4F6FB] text-[#94A3B8] text-[11px] font-semibold">완료</span>
                  </div>
                </button>
              ))}
            </div>
          </section>
        )}
        <div className="h-28" />
      </div>

      {/* FAB */}
      <div className="absolute bottom-6 left-0 right-0 flex justify-center pointer-events-none">
        <button
          onClick={onCreateTrip}
          className="h-[52px] px-7 rounded-2xl bg-[#3B7BF8] flex items-center gap-2 active:scale-95 transition-transform pointer-events-auto"
          style={{ boxShadow: "0 8px 28px rgba(59,123,248,0.5)" }}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5">
            <path d="M12 5v14M5 12h14"/>
          </svg>
          <span className="text-[15px] font-bold text-white">새 여행 만들기</span>
        </button>
      </div>
    </div>
  );
}
