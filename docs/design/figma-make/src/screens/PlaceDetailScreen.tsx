import { useState } from "react";
import React from "react";

interface Props {
  onBack: () => void;
  onAddToSchedule: () => void;
}

type Transport = "도보" | "대중교통" | "자동차";

const transportOptions: { type: Transport; icon: React.ReactElement; label: string; detail: string }[] = [
  { type: "도보", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="5" r="1"/><path d="M9 20l1-5 2 2 3-6"/></svg>, label: "도보", detail: "약 18분 · 1.2km" },
  { type: "대중교통", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2"/><circle cx="8.5" cy="17" r="1.5"/><circle cx="15.5" cy="17" r="1.5"/></svg>, label: "대중교통", detail: "약 9분 · 2정거장" },
  { type: "자동차", icon: <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 17H3a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v9a2 2 0 0 1-2 2h-2"/><circle cx="7.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/></svg>, label: "자동차", detail: "약 5분 · 주차 가능" },
];

export default function PlaceDetailScreen({ onBack, onAddToSchedule }: Props) {
  const [showModal, setShowModal] = useState(false);
  const [selectedTransport, setSelectedTransport] = useState<Transport>("대중교통");
  const [duration, setDuration] = useState(90);

  const adjustDuration = (d: number) => { const n = duration + d; if (n >= 30 && n <= 360) setDuration(n); };

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB] relative">
      {/* Hero */}
      <div className="relative h-[240px] flex-shrink-0">
        <img src="https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=390&h=240&fit=crop&auto=format" alt="경복궁" className="w-full h-full object-cover bg-[#CBD5E1]" />
        <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, rgba(0,0,0,0.3) 0%, transparent 50%, rgba(0,0,0,0.5) 100%)" }} />
        <button onClick={onBack} className="absolute top-4 left-5 w-9 h-9 rounded-full bg-black/30 backdrop-blur-sm flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <button className="absolute top-4 right-5 w-9 h-9 rounded-full bg-black/30 backdrop-blur-sm flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
        </button>
        <div className="absolute bottom-4 left-5 right-5">
          <span className="px-2.5 py-1 rounded-lg bg-[#ECFDF5] text-[#10B981] text-[11px] font-bold mb-2 inline-block">운영 중</span>
          <h1 className="text-white text-[26px] font-black" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>경복궁</h1>
          <p className="text-white/70 text-[13px]">서울 종로구 사직로 161</p>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {/* Stats */}
        <div className="bg-white px-5 py-4 flex items-center gap-4 border-b border-[#F4F6FB]">
          {[{ v: "4.8", l: "평점" }, { v: "09:00~18:00", l: "운영시간" }, { v: "3,000원", l: "입장료" }].map((s) => (
            <div key={s.l} className="flex-1 text-center">
              <p className="text-[16px] font-black text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>{s.v}</p>
              <p className="text-[11px] text-[#94A3B8] font-medium">{s.l}</p>
            </div>
          ))}
        </div>

        {/* Info rows */}
        <div className="bg-white mt-2">
          {[
            { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>, label: "주소", value: "서울 종로구 사직로 161" },
            { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>, label: "운영시간", value: "09:00 – 18:00 (월요일 휴무)" },
            { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>, label: "혼잡도", value: "현재 보통 · 오후 2시 예상 높음" },
            { icon: <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><path d="M20 17.58A5 5 0 0 0 18 8h-1.26A8 8 0 1 0 4 16.25"/><line x1="8" y1="16" x2="8.01" y2="16"/><line x1="12" y1="18" x2="12.01" y2="18"/><line x1="16" y1="16" x2="16.01" y2="16"/></svg>, label: "날씨", value: "오후 부분적 흐림 · 강수 15%" },
          ].map((row, i) => (
            <div key={i} className={`flex items-start gap-4 px-5 py-4 ${i < 3 ? "border-b border-[#F4F6FB]" : ""}`}>
              <div className="mt-0.5">{row.icon}</div>
              <div>
                <p className="text-[11px] font-bold text-[#94A3B8] uppercase tracking-wider mb-0.5">{row.label}</p>
                <p className="text-[14px] text-[#111827] font-medium">{row.value}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Map */}
        <div className="mt-2 mx-4 h-[130px] rounded-2xl overflow-hidden relative bg-[#EBF2FF]">
          <svg className="absolute inset-0 w-full h-full" viewBox="0 0 340 130" xmlns="http://www.w3.org/2000/svg">
            <defs><pattern id="mapg" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#3B7BF8" strokeWidth="0.3"/></pattern></defs>
            <rect width="340" height="130" fill="#EBF2FF"/>
            <rect width="340" height="130" fill="url(#mapg)" opacity="0.5"/>
            <path d="M 0 65 Q 90 50 170 65 Q 250 80 340 55" fill="none" stroke="white" strokeWidth="8" opacity="0.7"/>
            <path d="M 170 0 L 170 130" fill="none" stroke="white" strokeWidth="5" opacity="0.5"/>
            <circle cx="170" cy="65" r="12" fill="#3B7BF8"/>
            <circle cx="170" cy="65" r="22" fill="#3B7BF8" opacity="0.2"/>
            <text x="170" y="70" textAnchor="middle" fontSize="9" fill="white" fontWeight="800">경복궁</text>
          </svg>
        </div>
        <div className="h-24" />
      </div>

      {/* CTA */}
      <div className="bg-white px-5 py-4 flex gap-3" style={{ boxShadow: "0 -1px 0 #E2E8F0" }}>
        <button className="w-12 h-[52px] rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
        </button>
        <button onClick={() => setShowModal(true)} className="flex-1 h-[52px] rounded-xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>
          일정에 추가
        </button>
      </div>

      {/* Transport + duration modal */}
      {showModal && (
        <div className="absolute inset-0 z-40 flex flex-col justify-end" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-t-[32px] px-6 pt-5 pb-8" style={{ animation: "slide-up 0.25s ease-out" }}>
            <div className="w-10 h-1 bg-[#E2E8F0] rounded-full mx-auto mb-5" />
            <h2 className="text-[20px] font-black text-[#111827] mb-1" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>이동 수단 선택</h2>
            <p className="text-[13px] text-[#94A3B8] mb-5">경복궁까지 어떻게 이동하시겠어요?</p>
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
              <button onClick={() => { setShowModal(false); onAddToSchedule(); }} className="flex-[2] h-[50px] rounded-2xl font-bold text-[15px] text-white" style={{ background: "linear-gradient(135deg, #3B7BF8, #2457C5)" }}>일정에 추가</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
