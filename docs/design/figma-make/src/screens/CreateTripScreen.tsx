import { useState, useRef } from "react";

interface Props {
  onBack: () => void;
  onCreate: () => void;
}

const DEFAULT_IMAGE = "https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=390&h=180&fit=crop&auto=format";

export default function CreateTripScreen({ onBack, onCreate }: Props) {
  const [name, setName] = useState("서울 자유여행");
  const [nameError, setNameError] = useState(false);
  const [startDate, setStartDate] = useState<number | null>(12);
  const [endDate, setEndDate] = useState<number | null>(16);
  const [selecting, setSelecting] = useState<"start" | "end">("start");
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const calRows = [
    [null, null, null, null, null, 1, 2],
    [3, 4, 5, 6, 7, 8, 9],
    [10, 11, 12, 13, 14, 15, 16],
    [17, 18, 19, 20, 21, 22, 23],
    [24, 25, 26, 27, 28, 29, 30],
    [31, null, null, null, null, null, null],
  ];
  const dow = ["일", "월", "화", "수", "목", "금", "토"];

  const handleDay = (d: number) => {
    if (selecting === "start") {
      setStartDate(d); setEndDate(null); setSelecting("end");
    } else {
      if (d < (startDate ?? 0)) {
        setStartDate(d); setEndDate(null); setSelecting("end");
      } else {
        setEndDate(d); setSelecting("start");
      }
    }
  };

  const isInRange = (d: number) => startDate && endDate && d > startDate && d < endDate;
  const canCreate = !nameError && name.length >= 2 && startDate !== null;

  const handleImagePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const url = URL.createObjectURL(file);
    setImageUrl(url);
  };

  const coverSrc = imageUrl ?? DEFAULT_IMAGE;

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center gap-3">
        <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>새 여행 만들기</h1>
      </div>

      <div className="flex-1 overflow-y-auto px-5 py-5 space-y-5">
        {/* Cover image */}
        <div className="bg-white rounded-2xl overflow-hidden" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <div className="relative h-[160px]">
            <img src={coverSrc} alt="여행 커버" className="w-full h-full object-cover bg-[#CBD5E1]" />
            <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.5) 100%)" }} />
            <button onClick={() => fileRef.current?.click()}
              className="absolute bottom-3 right-3 flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-[13px] font-bold text-white"
              style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(8px)" }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
              {imageUrl ? "사진 변경" : "사진 업로드"}
            </button>
            {!imageUrl && (
              <div className="absolute bottom-3 left-3">
                <span className="text-[11px] text-white/60 font-medium">기본 이미지</span>
              </div>
            )}
            <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleImagePick} />
          </div>
        </div>

        {/* Name */}
        <div className="bg-white rounded-2xl p-5" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <label className="block text-[12px] font-bold text-[#94A3B8] uppercase tracking-wider mb-3">여행 이름</label>
          <input
            type="text"
            value={name}
            onChange={(e) => { setName(e.target.value); setNameError(e.target.value.length < 2 || e.target.value.length > 30); }}
            className={`w-full h-[50px] rounded-xl px-4 text-[16px] font-semibold text-[#111827] outline-none border-2 bg-[#F4F6FB] transition-colors ${nameError ? "border-[#EF4444]" : "border-transparent focus:border-[#3B7BF8]"}`}
          />
          {nameError && <p className="text-[12px] text-[#EF4444] mt-2 font-medium">2~30자 사이로 입력해주세요</p>}
        </div>

        {/* Calendar */}
        <div className="bg-white rounded-2xl overflow-hidden" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <div className="px-5 py-4">
            <label className="block text-[12px] font-bold text-[#94A3B8] uppercase tracking-wider mb-4">여행 일정</label>
            <div className="flex items-center justify-between mb-4">
              <button className="w-8 h-8 rounded-lg bg-[#F4F6FB] flex items-center justify-center">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2"><path d="M15 18l-6-6 6-6"/></svg>
              </button>
              <span className="text-[15px] font-bold text-[#111827]">2025년 8월</span>
              <button className="w-8 h-8 rounded-lg bg-[#F4F6FB] flex items-center justify-center">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2"><path d="M9 18l6-6-6-6"/></svg>
              </button>
            </div>
            <div className="grid grid-cols-7 mb-1">
              {dow.map((d) => <div key={d} className="text-center text-[11px] font-bold text-[#94A3B8] py-1">{d}</div>)}
            </div>
            <div>
              {calRows.map((row, ri) => (
                <div key={ri} className="grid grid-cols-7">
                  {row.map((d, ci) => {
                    const isSt = d === startDate;
                    const isEd = d === endDate;
                    const inRange = d !== null && !!isInRange(d as number);
                    return (
                      <div key={ci} className="relative h-10 flex items-center justify-center">
                        {inRange && <div className="absolute inset-0 bg-[#EBF2FF]" />}
                        {isSt && endDate && <div className="absolute top-0 bottom-0 right-0 w-1/2 bg-[#EBF2FF]" />}
                        {isEd && startDate && <div className="absolute top-0 bottom-0 left-0 w-1/2 bg-[#EBF2FF]" />}
                        <button
                          onClick={() => d && handleDay(d)}
                          disabled={!d}
                          className={`relative z-10 w-9 h-9 flex items-center justify-center text-[13px] font-semibold transition-colors ${
                            !d ? "opacity-0 pointer-events-none" :
                            isSt || isEd ? "rounded-full text-white" :
                            inRange ? "text-[#3B7BF8] font-semibold" :
                            "rounded-lg text-[#111827] hover:bg-[#F4F6FB]"
                          }`}
                          style={isSt || isEd ? { background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 2px 8px rgba(59,123,248,0.35)" } : {}}
                        >{d}</button>
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
          </div>

          {startDate && (
            <div className="border-t border-[#F4F6FB] px-5 py-3 flex items-center justify-between">
              <div className="text-center">
                <p className="text-[10px] text-[#94A3B8] font-bold uppercase">시작</p>
                <p className="text-[14px] font-black text-[#111827]">8/{startDate}</p>
              </div>
              <div className="flex-1 mx-4 h-px bg-[#E2E8F0]" />
              <div className="text-center">
                <p className="text-[10px] text-[#94A3B8] font-bold uppercase">종료</p>
                <p className="text-[14px] font-black text-[#111827]">{endDate ? `8/${endDate}` : "—"}</p>
              </div>
            </div>
          )}
        </div>
        <p className="text-[12px] text-[#94A3B8] text-center">최대 7일까지 설정할 수 있습니다</p>
      </div>

      <div className="px-5 pb-8 pt-4 bg-[#F4F6FB]">
        <button
          onClick={onCreate}
          disabled={!canCreate}
          className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white disabled:opacity-40 transition-opacity"
          style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: canCreate ? "0 4px 16px rgba(59,123,248,0.3)" : "none" }}
        >
          여행 만들고 일정 편집하기
        </button>
      </div>
    </div>
  );
}
