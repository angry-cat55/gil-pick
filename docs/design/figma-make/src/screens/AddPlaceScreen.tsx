import { useState } from "react";
import React from "react";

interface Props {
  onBack: () => void;
  onSelectPlace: (name: string) => void;
  onViewPlace?: () => void;
}

type Category = "전체" | "자연" | "문화·역사" | "음식" | "카페" | "쇼핑";
type Transport = "도보" | "대중교통" | "자동차";

interface Place {
  id: string;
  name: string;
  category: string;
  rating: number;
  status: "open" | "closing";
  statusLabel: string;
  image: string;
}

const places: Place[] = [
  { id: "1", name: "경복궁", category: "문화·역사", rating: 4.8, status: "open", statusLabel: "운영 중", image: "https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=80&h=80&fit=crop&auto=format" },
  { id: "2", name: "북촌 한옥마을", category: "문화·역사", rating: 4.6, status: "open", statusLabel: "운영 중", image: "https://images.unsplash.com/photo-1598939404966-7c5e8e3e6b39?w=80&h=80&fit=crop&auto=format" },
  { id: "3", name: "남산서울타워", category: "자연", rating: 4.7, status: "open", statusLabel: "운영 중", image: "https://images.unsplash.com/photo-1538485399081-7191377e8241?w=80&h=80&fit=crop&auto=format" },
  { id: "4", name: "창덕궁", category: "문화·역사", rating: 4.5, status: "closing", statusLabel: "18:00 마감", image: "https://images.unsplash.com/photo-1578469550956-0e16b69c6a3d?w=80&h=80&fit=crop&auto=format" },
  { id: "5", name: "인사동 거리", category: "쇼핑", rating: 4.3, status: "open", statusLabel: "운영 중", image: "https://images.unsplash.com/photo-1517154421773-0529f29ea451?w=80&h=80&fit=crop&auto=format" },
];

const transportOptions: { type: Transport; icon: React.ReactElement; label: string; detail: string }[] = [
  { type: "도보", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>, label: "도보", detail: "약 18분 · 1.2km" },
  { type: "대중교통", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>, label: "대중교통", detail: "약 9분 · 2정거장" },
  { type: "자동차", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 17H3a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v9a2 2 0 0 1-2 2h-2"/><circle cx="7.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/></svg>, label: "자동차", detail: "약 5분 · 주차 가능" },
];

export default function AddPlaceScreen({ onBack, onSelectPlace, onViewPlace }: Props) {
  const [query, setQuery] = useState("고궁");
  const [activeCategory, setActiveCategory] = useState<Category>("문화·역사");
  const [showModal, setShowModal] = useState(false);
  const [selectedTransport, setSelectedTransport] = useState<Transport>("대중교통");
  const [duration, setDuration] = useState(90);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const categories: Category[] = ["전체", "자연", "문화·역사", "음식", "카페", "쇼핑"];
  const selectedPlace = places.find((p) => p.id === selectedId);

  const adjustDuration = (d: number) => { const n = duration + d; if (n >= 30 && n <= 360) setDuration(n); };

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB] relative">
      <div className="bg-white px-5 pt-3 pb-4">
        <div className="flex items-center gap-3 mb-4">
          <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
          </button>
          <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>장소 추가</h1>
        </div>
        <div className="flex items-center gap-2 bg-[#F4F6FB] rounded-xl px-3.5 h-[44px] mb-3">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          <input type="text" placeholder="장소 이름 검색" value={query} onChange={(e) => setQuery(e.target.value)}
            className="flex-1 bg-transparent text-[14px] text-[#111827] placeholder-[#94A3B8] outline-none" />
          {query && <button onClick={() => setQuery("")}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/></svg></button>}
        </div>
        <div className="flex gap-2 overflow-x-auto">
          {categories.map((cat) => (
            <button key={cat} onClick={() => setActiveCategory(cat)}
              className={`flex-shrink-0 px-3.5 py-2 rounded-xl text-[13px] font-bold transition-all ${activeCategory === cat ? "bg-[#111827] text-white" : "bg-[#F4F6FB] text-[#6B7280]"}`}>
              {cat}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {query ? (
          <>
            <div className="flex items-center justify-between px-5 py-3">
              <span className="text-[13px] font-bold text-[#111827]">검색 결과 {places.length}곳</span>
              <button className="text-[12px] font-bold text-[#3B7BF8]">거리순 ▾</button>
            </div>
            {places.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-20 px-8 text-center">
                <div className="w-16 h-16 rounded-2xl bg-[#F4F6FB] flex items-center justify-center mb-4">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/><path d="M8.5 8.5l5 5M13.5 8.5l-5 5"/></svg>
                </div>
                <p className="text-[15px] font-bold text-[#111827] mb-1">'{query}' 검색 결과가 없어요</p>
                <p className="text-[13px] text-[#94A3B8] mb-5 leading-relaxed">띄어쓰기나 철자를 확인하거나<br/>카테고리로 찾아보세요</p>
                <button onClick={() => setActiveCategory("전체")} className="h-[44px] px-6 rounded-2xl border-2 border-[#E2E8F0] font-semibold text-[14px] text-[#6B7280]">카테고리로 찾기</button>
              </div>
            ) : (
            <div className="bg-white">
              {places.map((place, i) => (
                <div key={place.id}>
                  <div className="flex items-center gap-3 px-5 py-4">
                    <button onClick={onViewPlace} className="flex-shrink-0">
                      <img src={place.image} alt={place.name} className="w-[60px] h-[60px] rounded-xl object-cover bg-[#E8EDF5]" />
                    </button>
                    <button onClick={onViewPlace} className="flex-1 text-left">
                      <p className="font-bold text-[15px] text-[#111827]">{place.name}</p>
                      <p className="text-[12px] text-[#94A3B8] mb-1">{place.category}</p>
                      <div className="flex items-center gap-1.5">
                        <span className="text-[#FBBF24] text-[11px]">★</span>
                        <span className="text-[12px] font-semibold text-[#111827]">{place.rating}</span>
                        <span className={`text-[12px] font-semibold ${place.status === "closing" ? "text-[#F97316]" : "text-[#10B981]"}`}>{place.statusLabel}</span>
                      </div>
                    </button>
                    <button onClick={() => { setSelectedId(place.id); setShowModal(true); }}
                      className="w-8 h-8 rounded-lg bg-[#EBF2FF] flex items-center justify-center">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2.5"><path d="M12 5v14M5 12h14"/></svg>
                    </button>
                  </div>
                  {i < places.length - 1 && <div className="h-px bg-[#F4F6FB] mx-5" />}
                </div>
              ))}
            </div>
            )}
          </>
        ) : (
          <div className="flex flex-col items-center justify-center h-full px-8 text-center">
            <div className="w-16 h-16 rounded-2xl bg-[#F4F6FB] flex items-center justify-center mb-4">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#CBD5E1" strokeWidth="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
            </div>
            <p className="text-[16px] font-bold text-[#111827] mb-1">어떤 장소를 찾고 계세요?</p>
            <p className="text-[13px] text-[#94A3B8]">이름이나 카테고리로 검색하거나 위 카테고리를 눌러보세요</p>
          </div>
        )}
      </div>

      {/* Transport modal */}
      {showModal && selectedPlace && (
        <div className="absolute inset-0 z-40 flex flex-col justify-end" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-t-[32px] px-6 pt-5 pb-8">
            <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-5" />
            <h2 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>이동 수단 선택</h2>
            <p className="text-[13px] text-[#94A3B8] mb-5">{selectedPlace.name}까지 어떻게 이동하시겠어요?</p>
            <div className="space-y-2 mb-5">
              {transportOptions.map((opt) => (
                <button key={opt.type} onClick={() => setSelectedTransport(opt.type)}
                  className={`w-full flex items-center gap-3 px-4 py-3.5 rounded-2xl border-2 transition-all ${selectedTransport === opt.type ? "border-[#3B7BF8] bg-[#EBF2FF]" : "border-[#E2E8F0] bg-white"}`}>
                  <span className={selectedTransport === opt.type ? "text-[#3B7BF8]" : "text-[#94A3B8]"}>{opt.icon}</span>
                  <div className="flex-1 text-left">
                    <p className={`font-bold text-[14px] ${selectedTransport === opt.type ? "text-[#3B7BF8]" : "text-[#111827]"}`}>{opt.label}</p>
                    <p className="text-[12px] text-[#94A3B8]">{opt.detail}</p>
                  </div>
                  {selectedTransport === opt.type && <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#3B7BF8" strokeWidth="2.5"><polyline points="20 6 9 17 4 12"/></svg>}
                </button>
              ))}
            </div>
            <p className="text-[14px] font-bold text-[#111827] mb-3">체류 시간</p>
            <div className="flex items-center justify-between bg-[#F4F6FB] rounded-2xl px-4 py-3 mb-5">
              <button onClick={() => adjustDuration(-30)} className="w-10 h-10 rounded-full bg-white flex items-center justify-center text-xl font-black text-[#111827]" style={{ boxShadow: "0 2px 6px rgba(0,0,0,0.08)" }}>−</button>
              <span className="text-[22px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{duration}분</span>
              <button onClick={() => adjustDuration(30)} className="w-10 h-10 rounded-full flex items-center justify-center text-white text-xl font-black" style={{ background: "linear-gradient(135deg, #3B7BF8, #2457C5)" }}>+</button>
            </div>
            <div className="flex gap-3">
              <button onClick={() => setShowModal(false)} className="flex-1 h-[50px] rounded-2xl text-[14px] font-semibold text-[#6B7280] bg-[#F4F6FB]">취소</button>
              <button onClick={() => { setShowModal(false); onSelectPlace(selectedPlace.name); }} className="flex-[2] h-[50px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8, #2457C5)" }}>일정에 추가</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
