import { useState, useRef } from "react";

interface Props {
  onBack: () => void;
  onSave: () => void;
}

const DEFAULT_IMAGE = "https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=390&h=180&fit=crop&auto=format";

export default function EditTripScreen({ onBack, onSave }: Props) {
  const [name, setName] = useState("서울 자유여행");
  const [nameError, setNameError] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const coverSrc = imageUrl ?? DEFAULT_IMAGE;

  const handleImagePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImageUrl(URL.createObjectURL(file));
  };

  return (
    <div className="flex flex-col h-full bg-[#F4F6FB] relative">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
          </button>
          <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>여행 수정</h1>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-5 space-y-4">
        {/* Cover image */}
        <div className="bg-white rounded-2xl overflow-hidden" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <div className="relative h-[160px]">
            <img src={coverSrc} alt="여행 커버" className="w-full h-full object-cover bg-[#CBD5E1]" />
            <div className="absolute inset-0" style={{ background: "linear-gradient(to bottom, transparent 40%, rgba(0,0,0,0.55) 100%)" }} />
            <div className="absolute bottom-3 left-4 right-4 flex items-end justify-between">
              <div>
                <p className="text-white/60 text-[11px]">{imageUrl ? "커스텀 이미지" : "기본 이미지"}</p>
              </div>
              <div className="flex gap-2">
                <button onClick={() => fileRef.current?.click()}
                  className="flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-[12px] font-bold text-white"
                  style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(8px)" }}>
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                  사진 변경
                </button>
                {imageUrl && (
                  <button onClick={() => setImageUrl(null)}
                    className="px-3.5 py-2 rounded-xl text-[12px] font-bold text-white"
                    style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(8px)" }}>
                    기본으로
                  </button>
                )}
              </div>
            </div>
            <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleImagePick} />
          </div>
        </div>

        <div className="bg-white rounded-2xl p-5" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <label className="block text-[11px] font-black text-[#94A3B8] uppercase tracking-wider mb-2">여행 이름</label>
          <input
            type="text"
            value={name}
            onChange={(e) => { setName(e.target.value); setNameError(e.target.value.length < 2 || e.target.value.length > 30); }}
            className={`w-full h-[50px] rounded-xl px-4 text-[16px] font-semibold text-[#111827] outline-none border-2 bg-[#F4F6FB] transition-colors ${nameError ? "border-[#EF4444]" : "border-transparent focus:border-[#3B7BF8]"}`}
          />
          {nameError && <p className="text-[12px] text-[#EF4444] mt-2">2~30자 사이로 입력해주세요</p>}
        </div>

        <div className="bg-white rounded-2xl p-5" style={{ boxShadow: "0 1px 4px rgba(0,0,0,0.06)" }}>
          <label className="block text-[11px] font-black text-[#94A3B8] uppercase tracking-wider mb-3">여행 기간</label>
          <div className="flex items-center gap-3">
            <div className="flex-1 h-[48px] rounded-xl bg-[#F4F6FB] flex items-center justify-between px-4">
              <span className="text-[14px] font-semibold text-[#111827]">2025. 8. 12</span>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
            </div>
            <span className="text-[#94A3B8] font-semibold">–</span>
            <div className="flex-1 h-[48px] rounded-xl bg-[#F4F6FB] flex items-center justify-between px-4">
              <span className="text-[14px] font-semibold text-[#111827]">2025. 8. 16</span>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
            </div>
          </div>
        </div>

        <div className="bg-[#FEF2F2] rounded-2xl p-4 flex items-start gap-3">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2" className="flex-shrink-0 mt-0.5"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          <div className="flex-1">
            <p className="text-[13px] font-semibold text-[#EF4444] mb-1">여행 기간을 줄이면 일부 일정이 삭제될 수 있어요</p>
            <p className="text-[12px] text-[#F87171]">확인하고 저장하세요</p>
          </div>
          <input type="checkbox" className="mt-0.5 accent-[#EF4444]" />
        </div>
      </div>

      <div className="px-4 pb-8 pt-3 space-y-2">
        <button onClick={onSave} disabled={nameError} className="w-full h-[54px] rounded-2xl font-bold text-[15px] text-white disabled:opacity-40" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)", boxShadow: "0 4px 16px rgba(59,123,248,0.3)" }}>
          저장
        </button>
        <button onClick={() => setDeleteConfirm(true)} className="w-full h-[48px] rounded-2xl font-semibold text-[14px] text-[#EF4444] bg-[#FEF2F2]">
          여행 삭제
        </button>
      </div>

      {deleteConfirm && (
        <div className="absolute inset-0 z-50 flex items-center justify-center px-5" style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(4px)" }}>
          <div className="bg-white rounded-3xl p-6 w-full shadow-2xl">
            <h2 className="text-[20px] font-black text-[#111827] mb-2" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>여행을 삭제할까요?</h2>
            <p className="text-[13px] text-[#6B7280] mb-6">삭제한 여행은 복구할 수 없습니다</p>
            <button onClick={() => setDeleteConfirm(false)} className="w-full h-[52px] rounded-2xl font-bold text-[15px] text-white mb-2" style={{ background: "linear-gradient(135deg, #3B7BF8 0%, #2457C5 100%)" }}>취소</button>
            <button onClick={onBack} className="w-full text-[14px] font-semibold text-[#EF4444] py-2">삭제하기</button>
          </div>
        </div>
      )}
    </div>
  );
}
