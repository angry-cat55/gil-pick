import { useState } from "react";

interface Props {
  onBack: () => void;
  onSelectPlace: (name: string) => void;
}

type Category = "전체" | "명소" | "음식" | "카페" | "쇼핑" | "자연";

interface PlaceResult {
  id: string;
  name: string;
  distance: string;
  warning?: string;
  closing?: string;
  category: Category;
  markerN: number;
  cx: number;
  cy: number;
}

const results: PlaceResult[] = [
  { id: "1", name: "경복궁", distance: "0.8km", category: "명소", markerN: 1, cx: 155, cy: 145 },
  { id: "2", name: "창덕궁", distance: "1.4km", category: "명소", markerN: 2, cx: 255, cy: 115 },
  { id: "3", name: "인사동거리", distance: "1.9km", category: "명소", markerN: 3, cx: 205, cy: 195 },
  { id: "4", name: "북촌한옥마을", distance: "2.3km", warning: "혼잡", category: "명소", markerN: 4, cx: 305, cy: 165 },
  { id: "5", name: "남산타워", distance: "3.1km", closing: "20:00마감", category: "명소", markerN: 5, cx: 135, cy: 230 },
];

const categories: Category[] = ["전체", "명소", "음식", "카페", "쇼핑", "자연"];

export default function MapSearchScreen({ onBack, onSelectPlace }: Props) {
  const [activeCategory, setActiveCategory] = useState<Category>("전체");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const filtered = results.filter((r) => {
    const matchCat = activeCategory === "전체" || r.category === activeCategory;
    const matchQ = !query || r.name.includes(query);
    return matchCat && matchQ;
  });

  return (
    <div className="flex flex-col h-full relative overflow-hidden" style={{ background: "#D6E8F5" }}>
      {/* Map */}
      <div className="absolute inset-0">
        <svg className="w-full h-full" viewBox="0 0 390 540" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid slice">
          <defs>
            <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
              <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#3B7BF8" strokeWidth="0.3" opacity="0.3"/>
            </pattern>
          </defs>
          <rect width="390" height="540" fill="#D6E8F5"/>
          <rect width="390" height="540" fill="url(#grid)"/>

          {/* Park/green areas */}
          <rect x="40" y="100" width="90" height="70" rx="8" fill="#C3DBA8" opacity="0.7"/>
          <rect x="260" y="80" width="80" height="60" rx="8" fill="#C3DBA8" opacity="0.6"/>
          <rect x="70" y="310" width="60" height="50" rx="6" fill="#C3DBA8" opacity="0.55"/>

          {/* Roads */}
          <path d="M 0 190 Q 130 170 195 185 Q 260 200 390 172" fill="none" stroke="white" strokeWidth="10" opacity="0.9"/>
          <path d="M 180 0 L 175 540" fill="none" stroke="white" strokeWidth="8" opacity="0.85"/>
          <path d="M 0 310 Q 195 295 390 320" fill="none" stroke="white" strokeWidth="7" opacity="0.8"/>
          <path d="M 85 0 Q 90 270 65 540" fill="none" stroke="white" strokeWidth="5" opacity="0.6"/>
          <path d="M 310 0 Q 305 270 330 540" fill="none" stroke="white" strokeWidth="5" opacity="0.6"/>

          {/* City blocks */}
          <rect x="20" y="30" width="55" height="55" rx="5" fill="white" opacity="0.3"/>
          <rect x="100" y="20" width="65" height="45" rx="5" fill="white" opacity="0.3"/>
          <rect x="210" y="15" width="75" height="55" rx="5" fill="white" opacity="0.28"/>
          <rect x="320" y="25" width="55" height="45" rx="5" fill="white" opacity="0.3"/>
          <rect x="20" y="220" width="50" height="60" rx="5" fill="white" opacity="0.25"/>
          <rect x="210" y="240" width="65" height="42" rx="5" fill="white" opacity="0.25"/>
          <rect x="320" y="225" width="52" height="55" rx="5" fill="white" opacity="0.25"/>

          {/* Markers */}
          {filtered.map((p) => (
            <g key={p.id} onClick={() => setSelectedId(p.id === selectedId ? null : p.id)} style={{ cursor: "pointer" }}>
              {selectedId === p.id && (
                <circle cx={p.cx} cy={p.cy} r={28} fill="#3B7BF8" opacity="0.15"/>
              )}
              <circle cx={p.cx} cy={p.cy} r={selectedId === p.id ? 18 : 14}
                fill={selectedId === p.id ? "#3B7BF8" : "white"}
                stroke="#3B7BF8" strokeWidth="2"
                style={{ filter: "drop-shadow(0 2px 4px rgba(59,123,248,0.3))", transition: "all 0.2s" }}
              />
              <text x={p.cx} y={p.cy + 5} textAnchor="middle" fontSize="11" fontWeight="800"
                fill={selectedId === p.id ? "white" : "#3B7BF8"}
              >{p.markerN}</text>
              {p.warning && (
                <>
                  <rect x={p.cx + 14} y={p.cy - 28} width="32" height="16" rx="8" fill="#F97316"/>
                  <text x={p.cx + 30} y={p.cy - 17} textAnchor="middle" fontSize="8" fontWeight="700" fill="white">혼잡</text>
                </>
              )}
              {p.closing && (
                <>
                  <rect x={p.cx + 14} y={p.cy - 28} width="48" height="16" rx="8" fill="#6B7280"/>
                  <text x={p.cx + 38} y={p.cy - 17} textAnchor="middle" fontSize="8" fontWeight="700" fill="white">20:00마감</text>
                </>
              )}
            </g>
          ))}
        </svg>
      </div>

      {/* Search bar */}
      <div className="relative z-10 px-4 pt-4">
        <div className="flex items-center gap-3 bg-white/96 rounded-2xl px-4 h-[52px]" style={{ backdropFilter: "blur(12px)", boxShadow: "0 4px 20px rgba(0,0,0,0.12)" }}>
          <button onClick={onBack} className="flex-shrink-0">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5">
              <path d="M19 12H5M12 19l-7-7 7-7"/>
            </svg>
          </button>
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="관광지, 이름, 카테고리 검색"
            className="flex-1 bg-transparent outline-none text-[14px] text-[#111827] placeholder-[#94A3B8]"
          />
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
        </div>
      </div>

      {/* Category chips */}
      <div className="relative z-10 px-4 mt-3">
        <div className="flex gap-2 overflow-x-auto pb-0.5">
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`px-4 py-2 rounded-full text-[13px] font-semibold whitespace-nowrap transition-colors ${
                activeCategory === cat
                  ? "bg-[#3B7BF8] text-white"
                  : "bg-white/90 text-[#6B7280]"
              }`}
              style={{ backdropFilter: "blur(8px)", boxShadow: activeCategory === cat ? "0 2px 8px rgba(59,123,248,0.35)" : "0 1px 4px rgba(0,0,0,0.08)" }}
            >
              {cat}
            </button>
          ))}
        </div>
      </div>

      {/* Bottom sheet */}
      <div className="absolute bottom-0 left-0 right-0 z-10 bg-white rounded-t-3xl" style={{ maxHeight: "55%", boxShadow: "0 -4px 24px rgba(0,0,0,0.12)" }}>
        <div className="flex justify-center pt-3 pb-2">
          <div className="w-10 h-1.5 bg-[#E2E8F0] rounded-full" />
        </div>

        <div className="px-5 pb-2 flex items-center justify-between">
          <p className="text-[15px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>
            검색 결과 <span className="text-[#3B7BF8]">{filtered.length}</span>곳
          </p>
          <p className="text-[12px] text-[#94A3B8]">경복궁 기준 2km 이내</p>
        </div>

        <div className="overflow-y-auto" style={{ maxHeight: "calc(55vh - 80px)" }}>
          {filtered.map((place, i) => (
            <div key={place.id}>
              <div className={`flex items-center gap-4 px-5 py-4 transition-colors ${selectedId === place.id ? "bg-[#F8FBFF]" : ""}`}
                onClick={() => setSelectedId(place.id === selectedId ? null : place.id)}
              >
                <div className={`w-9 h-9 rounded-xl border-2 flex items-center justify-center flex-shrink-0 transition-colors ${
                  selectedId === place.id ? "bg-[#3B7BF8] border-[#3B7BF8]" : "bg-white border-[#E2E8F0]"
                }`}>
                  <span className={`text-[12px] font-black ${selectedId === place.id ? "text-white" : "text-[#6B7280]"}`} style={{ fontFamily: "Outfit, sans-serif" }}>{place.markerN}</span>
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-0.5">
                    <p className="text-[15px] font-semibold text-[#111827]">{place.name}</p>
                    {place.warning && (
                      <span className="px-2 py-0.5 rounded-full bg-[#FFF7ED] text-[#F97316] text-[11px] font-bold">{place.warning}</span>
                    )}
                    {place.closing && (
                      <span className="px-2 py-0.5 rounded-full bg-[#F4F6FB] text-[#6B7280] text-[11px] font-medium">{place.closing}</span>
                    )}
                  </div>
                  <p className="text-[13px] text-[#94A3B8]">{place.distance}</p>
                </div>
                <button
                  onClick={(e) => { e.stopPropagation(); onSelectPlace(place.name); }}
                  className="px-3 h-8 rounded-xl text-[13px] font-bold text-[#3B7BF8] bg-[#EBF2FF]"
                >
                  선택
                </button>
              </div>
              {i < filtered.length - 1 && <div className="h-px bg-[#F4F6FB] mx-5" />}
            </div>
          ))}
          <div className="h-6" />
        </div>
      </div>
    </div>
  );
}
