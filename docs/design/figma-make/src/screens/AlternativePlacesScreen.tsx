import { useState } from "react";

interface Props {
  onBack: () => void;
  onKeepSchedule: () => void;
  onSearchManually: () => void;
  onRouteCompare: () => void;
}

interface Candidate {
  id: string;
  name: string;
  category: string;
  distance: string;
  rating: number;
  closing: string;
  closingWarning: boolean;
  benefit: string;
  isTop?: boolean;
}

const candidates: Candidate[] = [
  { id: "1", name: "창덕궁", category: "궁궐", distance: "0.8km", rating: 4.6, closing: "18:00 마감", closingWarning: false, benefit: "실내 관람 가능 · 현재보다 8분 가까워요", isTop: true },
  { id: "2", name: "북촌한옥마을", category: "전통마을", distance: "1.2km", rating: 4.4, closing: "종일 개방", closingWarning: false, benefit: "혼잡도 보통 · 이동 시간 4분 증가" },
  { id: "3", name: "인사동", category: "문화거리", distance: "1.5km", rating: 4.3, closing: "21:00 마감", closingWarning: false, benefit: "실내 상점 많음 · 비 예보 영향 적어요" },
  { id: "4", name: "덕수궁", category: "궁궐", distance: "2.1km", rating: 4.2, closing: "17:00 마감", closingWarning: true, benefit: "혼잡도 낮음 · 이동 시간 11분 증가" },
];

interface PropsExt extends Props { hasResults?: boolean; }

export default function AlternativePlacesScreen({ onBack, onKeepSchedule, onSearchManually, onRouteCompare, hasResults = true }: PropsExt) {

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      {/* Map */}
      <div className="relative flex-shrink-0" style={{ height: "38%" }}>
        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 390 300" xmlns="http://www.w3.org/2000/svg">
          <defs><pattern id="amg" width="28" height="28" patternUnits="userSpaceOnUse"><path d="M 28 0 L 0 0 0 28" fill="none" stroke="#3B7BF8" strokeWidth="0.3"/></pattern></defs>
          <rect width="390" height="300" fill="#EBF2FF"/>
          <rect width="390" height="300" fill="url(#amg)" opacity="0.5"/>
          <path d="M 0 150 Q 100 130 195 150 Q 290 170 390 140" fill="none" stroke="white" strokeWidth="9" opacity="0.7"/>
          <path d="M 195 0 L 195 300" fill="none" stroke="white" strokeWidth="6" opacity="0.5"/>
          <circle cx="195" cy="105" r="18" fill="#F97316" opacity="0.95"/>
          <circle cx="195" cy="105" r="28" fill="#F97316" opacity="0.15"/>
          <text x="195" y="111" textAnchor="middle" fontSize="13" fill="white" fontWeight="900">!</text>
          <circle cx="105" cy="200" r="14" fill="#3B7BF8"/>
          <text x="105" y="205" textAnchor="middle" fontSize="10" fill="white" fontWeight="800">1</text>
          <circle cx="295" cy="165" r="12" fill="#3B7BF8" opacity="0.8"/>
          <text x="295" y="170" textAnchor="middle" fontSize="10" fill="white" fontWeight="800">2</text>
          <circle cx="220" cy="185" r="14" fill="#3B7BF8" opacity="0.9"/>
          <text x="220" y="191" textAnchor="middle" fontSize="10" fill="white" fontWeight="800">3</text>
        </svg>
        <button onClick={onBack} className="absolute top-12 left-4 w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center" style={{ boxShadow: "0 2px 8px rgba(0,0,0,0.15)" }}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <div className="absolute bottom-0 left-0 right-0 h-10" style={{ background: "linear-gradient(to top, white, transparent)" }} />
      </div>

      <div className="flex-1 bg-white -mt-4 relative z-10 rounded-t-3xl overflow-y-auto" style={{ boxShadow: "0 -4px 20px rgba(0,0,0,0.08)" }}>
        <div className="flex justify-center pt-3 pb-2">
          <div className="w-10 h-1 bg-[#E2E8F0] rounded-full" />
        </div>
        <div className="px-5">
          <div className="flex items-center gap-3 mb-3">
            <div className="w-10 h-10 rounded-2xl bg-[#FFF7ED] flex items-center justify-center flex-shrink-0">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
            </div>
            <div>
              <h2 className="text-[16px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>경복궁 · 방문 어려움 감지</h2>
              <p className="text-[12px] text-[#94A3B8]">오후 2시 이후 강한 비 + 매우 높은 혼잡</p>
            </div>
          </div>
          <div className="flex gap-2 mb-4">
            {[{ e: "🌧", l: "오후 강수" }, { e: "👥", l: "매우 혼잡" }, { e: "⏰", l: "마감 임박" }].map((t) => (
              <span key={t.l} className="px-3 py-1.5 rounded-xl bg-[#FFF7ED] text-[12px] font-bold text-[#C2410C]">{t.e} {t.l}</span>
            ))}
          </div>
          <div className="h-px bg-[#F4F6FB] mb-4" />

          {hasResults ? (
            <>
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-[15px] font-black text-[#111827]">추천 후보 {candidates.length}곳</h3>
                <button onClick={onSearchManually} className="flex items-center gap-1.5 text-[13px] font-bold text-[#3B7BF8]">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
                  직접 검색
                </button>
              </div>
              <div className="space-y-0">
                {candidates.map((c, i) => (
                  <div key={c.id}>
                    <div className={`py-4 ${c.isTop ? "-mx-5 px-5 bg-[#F0F6FF] rounded-xl mb-1" : ""}`}>
                      <div className="flex items-start gap-3">
                        <div className={`w-8 h-8 rounded-xl flex items-center justify-center flex-shrink-0 mt-0.5 ${c.isTop ? "bg-[#3B7BF8]" : "bg-[#F4F6FB]"}`}>
                          <span className={`text-[12px] font-black ${c.isTop ? "text-white" : "text-[#94A3B8]"}`}>{i + 1}</span>
                        </div>
                        <div className="flex-1">
                          <div className="flex items-center gap-1.5 mb-0.5">
                            <p className={`font-black text-[16px] ${c.isTop ? "text-[#3B7BF8]" : "text-[#111827]"}`} style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>{c.name}</p>
                            {c.isTop && <span className="px-2 py-0.5 rounded-md bg-[#3B7BF8] text-white text-[10px] font-black">TOP</span>}
                          </div>
                          <p className="text-[12px] text-[#94A3B8] mb-1">{c.category} · {c.distance} · ★{c.rating}</p>
                          <p className="text-[12px] text-[#6B7280]">{c.benefit}</p>
                        </div>
                        <div className="flex flex-col items-end gap-2 flex-shrink-0">
                          <span className={`text-[12px] font-semibold ${c.closingWarning ? "text-[#F97316]" : "text-[#94A3B8]"}`}>{c.closing}</span>
                          <button onClick={onRouteCompare}
                            className={`px-4 py-2 rounded-xl text-[13px] font-black ${c.isTop ? "bg-[#3B7BF8] text-white" : "text-[#3B7BF8]"}`}
                            style={c.isTop ? { boxShadow: "0 2px 8px rgba(59,123,248,0.3)" } : {}}>
                            {c.isTop ? "경로 비교" : "비교"}
                          </button>
                        </div>
                      </div>
                    </div>
                    {i < candidates.length - 1 && <div className="h-px bg-[#F4F6FB]" />}
                  </div>
                ))}
              </div>
              <div className="mt-5 mb-6">
                <button onClick={onKeepSchedule} className="w-full h-[50px] rounded-2xl border-2 border-[#E2E8F0] font-semibold text-[14px] text-[#6B7280]">
                  기존 일정 그대로 진행
                </button>
              </div>
            </>
          ) : (
            <>
              <div className="flex flex-col items-center text-center py-8">
                <p className="text-[16px] font-bold text-[#111827] mb-1">2km 안에 추천할 장소가 없어요</p>
                <p className="text-[13px] text-[#94A3B8]">반경을 넓혀봤지만 적합한 곳을 찾지 못했습니다</p>
              </div>
              <button onClick={onKeepSchedule} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>기존 일정 그대로 진행</button>
              <button onClick={onSearchManually} className="w-full h-[48px] rounded-2xl font-semibold text-[14px] text-[#6B7280] bg-[#F4F6FB] mb-6">직접 검색해서 고르기</button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
