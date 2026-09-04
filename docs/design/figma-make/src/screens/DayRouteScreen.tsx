interface Props {
  onBack: () => void;
}

interface RouteStop {
  id: string;
  name: string;
  time: string;
  status: "done" | "active" | "upcoming";
  cx: number;
  cy: number;
}

const stops: RouteStop[] = [
  { id: "1", name: "경복궁", time: "완료", status: "done", cx: 80, cy: 70 },
  { id: "2", name: "북촌한옥마을", time: "이동 중", status: "active", cx: 200, cy: 110 },
  { id: "3", name: "인사동거리", time: "오후 4:00", status: "upcoming", cx: 280, cy: 65 },
  { id: "4", name: "남산서울타워", time: "오후 6:30", status: "upcoming", cx: 170, cy: 200 },
];

export default function DayRouteScreen({ onBack }: Props) {
  return (
    <div className="flex flex-col h-full bg-[#0F172A]">
      {/* Header */}
      <div className="px-5 pt-3 pb-4 flex items-center gap-3">
        <button onClick={onBack} className="w-9 h-9 rounded-full bg-white/10 backdrop-blur-sm flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <div>
          <h1 className="text-[18px] font-black text-white" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>2일차 경로</h1>
          <p className="text-[12px] text-white/50">5월 21일 · 4곳</p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <span className="px-2.5 py-1 rounded-lg bg-[#10B981]/20 text-[#10B981] text-[11px] font-bold">여행 중</span>
        </div>
      </div>

      {/* Full-screen map */}
      <div className="flex-1 relative overflow-hidden">
        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 390 500" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid slice">
          <defs>
            <pattern id="drg" width="30" height="30" patternUnits="userSpaceOnUse">
              <path d="M 30 0 L 0 0 0 30" fill="none" stroke="#1E3A5F" strokeWidth="0.4"/>
            </pattern>
            <radialGradient id="glow1" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#3B7BF8" stopOpacity="0.3"/>
              <stop offset="100%" stopColor="#3B7BF8" stopOpacity="0"/>
            </radialGradient>
            <radialGradient id="glow2" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#10B981" stopOpacity="0.25"/>
              <stop offset="100%" stopColor="#10B981" stopOpacity="0"/>
            </radialGradient>
            <filter id="blur4">
              <feGaussianBlur stdDeviation="4"/>
            </filter>
          </defs>
          <rect width="390" height="500" fill="#0F1A2E"/>
          <rect width="390" height="500" fill="url(#drg)"/>

          {/* Road network */}
          <path d="M 0 200 Q 130 180 200 200 Q 300 220 390 185" fill="none" stroke="#1E3A5F" strokeWidth="12" opacity="0.8"/>
          <path d="M 0 200 Q 130 180 200 200 Q 300 220 390 185" fill="none" stroke="#263A5F" strokeWidth="6"/>
          <path d="M 195 0 L 195 500" fill="none" stroke="#1E3A5F" strokeWidth="8" opacity="0.6"/>
          <path d="M 195 0 L 195 500" fill="none" stroke="#263A5F" strokeWidth="4"/>
          <path d="M 50 0 Q 80 150 100 300 Q 120 400 90 500" fill="none" stroke="#1A2E4A" strokeWidth="5" opacity="0.5"/>
          <path d="M 300 0 Q 320 100 290 250 Q 270 380 310 500" fill="none" stroke="#1A2E4A" strokeWidth="5" opacity="0.5"/>

          {/* Blocks */}
          {[[30,50,60,40],[120,60,70,50],[250,40,80,45],[320,70,55,60],[30,130,55,50],[150,140,65,45],[270,130,60,40],[330,150,50,55],[30,250,70,50],[130,260,60,40],[250,260,75,50],[330,250,55,45],[30,360,65,50],[150,365,60,45],[270,365,70,40]].map(([x,y,w,h], i) => (
            <rect key={i} x={x} y={y} width={w} height={h} rx="4" fill="#152033" opacity="0.9"/>
          ))}

          {/* Route path with glow */}
          <path d="M 80 70 C 140 90 160 95 200 110 C 240 125 270 100 280 65 C 250 90 215 160 170 200" fill="none" stroke="#3B7BF8" strokeWidth="4" strokeDasharray="10 4" opacity="0.3" filter="url(#blur4)"/>
          <path d="M 80 70 C 140 90 160 95 200 110 C 240 125 270 100 280 65 C 250 90 215 160 170 200" fill="none" stroke="#3B7BF8" strokeWidth="2.5" strokeDasharray="10 4"/>

          {/* Glows around stops */}
          {stops.map((s) => (
            <ellipse key={s.id} cx={s.cx} cy={s.cy} rx="28" ry="28" fill={s.status === "done" ? "url(#glow2)" : s.status === "active" ? "url(#glow1)" : "url(#glow1)"} opacity={s.status === "upcoming" ? "0.5" : "1"}/>
          ))}

          {/* Stop markers */}
          {stops.map((s, i) => (
            <g key={s.id}>
              <circle cx={s.cx} cy={s.cy} r={s.status === "active" ? 14 : 10}
                fill={s.status === "done" ? "#10B981" : s.status === "active" ? "#3B7BF8" : "#1E3A5F"}
                stroke={s.status === "active" ? "#7BADFF" : "transparent"} strokeWidth="2"/>
              {s.status === "done" ? (
                <text x={s.cx} y={s.cy + 1} textAnchor="middle" dominantBaseline="middle" fontSize="9" fill="white" fontWeight="800">✓</text>
              ) : (
                <text x={s.cx} y={s.cy + 1} textAnchor="middle" dominantBaseline="middle" fontSize="10" fill="white" fontWeight="800">{i + 1}</text>
              )}
              <rect x={s.cx - 42} y={s.cy - 38} width="84" height="22" rx="6" fill="rgba(15,26,46,0.88)"/>
              <text x={s.cx} y={s.cy - 26} textAnchor="middle" fontSize="11" fill="white" fontWeight="700">{s.name}</text>
            </g>
          ))}

          {/* Current location pulse */}
          <circle cx="200" cy="110" r="22" fill="#3B7BF8" opacity="0.15"/>
          <circle cx="200" cy="110" r="15" fill="#3B7BF8" opacity="0.2"/>
        </svg>

        {/* Legend bottom sheet */}
        <div className="absolute bottom-0 left-0 right-0 rounded-t-3xl px-5 pt-4 pb-6" style={{ background: "rgba(15,26,46,0.9)", backdropFilter: "blur(20px)" }}>
          <div className="w-10 h-1 bg-white/20 rounded-full mx-auto mb-4" />
          <div className="flex items-center gap-3 mb-3">
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-[#10B981]" />
              <span className="text-[12px] text-white/60">완료</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-[#3B7BF8]" />
              <span className="text-[12px] text-white/60">이동 중</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-[#1E3A5F]" />
              <span className="text-[12px] text-white/60">예정</span>
            </div>
            <div className="ml-auto flex items-center gap-1">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" opacity="0.4"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
              <span className="text-[12px] text-white/40">지도 이동 가능</span>
            </div>
          </div>
          <div className="flex gap-2">
            {stops.map((s, i) => (
              <div key={s.id} className={`flex-1 rounded-xl px-2.5 py-2 ${s.status === "active" ? "bg-[#3B7BF8]/20" : "bg-white/5"}`}>
                <div className="flex items-center gap-1 mb-1">
                  <div className={`w-1.5 h-1.5 rounded-full ${s.status === "done" ? "bg-[#10B981]" : s.status === "active" ? "bg-[#3B7BF8]" : "bg-white/20"}`} />
                  <span className={`text-[9px] font-bold ${s.status === "active" ? "text-[#7BADFF]" : "text-white/40"}`}>{i + 1}</span>
                </div>
                <p className={`text-[11px] font-bold leading-tight ${s.status === "active" ? "text-white" : s.status === "done" ? "text-white/60" : "text-white/40"}`}>{s.name}</p>
                <p className={`text-[9px] mt-0.5 ${s.status === "active" ? "text-[#7BADFF]" : "text-white/30"}`}>{s.time}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
