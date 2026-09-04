import React from "react";

interface Props {
  onBack: () => void;
}

interface NotifItem {
  id: string;
  title: string;
  desc: string;
  time: string;
  unread: boolean;
  icon: "place" | "location" | "check" | "alert" | "calendar";
}

const todayItems: NotifItem[] = [
  { id: "1", title: "다음 장소 변경을 추천해요", desc: "인사동거리가 매우 혼잡해요. 대체 장소를 확인해보세요.", time: "3분 전", unread: true, icon: "place" },
  { id: "2", title: "도착하셨나요?", desc: "북촌한옥마을 근처에서 6분 머물고 있어요.", time: "12분 전", unread: true, icon: "location" },
  { id: "3", title: "도착으로 자동 처리했어요", desc: "경복궁 도착 · 5분 안에 되돌릴 수 있어요.", time: "오전 11:32", unread: false, icon: "check" },
];

const yesterdayItems: NotifItem[] = [
  { id: "4", title: "경로를 다시 계산하지 못했어요", desc: "기존 일정과 도착 시각은 그대로 유지했습니다.", time: "오후 6:04", unread: false, icon: "alert" },
  { id: "5", title: "내일 여행이 시작돼요", desc: "서울 3박 4일 · 1일차에 3곳을 방문합니다.", time: "오후 9:00", unread: false, icon: "calendar" },
];

const NotifIcon = ({ type, unread }: { type: NotifItem["icon"]; unread: boolean }) => {
  const bg = unread ? "bg-[#EBF2FF]" : "bg-[#F4F6FB]";
  const color = unread ? "#3B7BF8" : "#94A3B8";
  const icons: Record<typeof type, React.ReactElement> = {
    place: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>,
    location: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/></svg>,
    check: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><circle cx="12" cy="12" r="10"/><polyline points="9 12 11 14 15 10"/></svg>,
    alert: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>,
    calendar: <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>,
  };
  return <div className={`w-10 h-10 rounded-2xl ${bg} flex items-center justify-center flex-shrink-0`}>{icons[type]}</div>;
};

export default function NotificationsScreen({ onBack }: Props) {
  return (
    <div className="flex flex-col h-full bg-[#F4F6FB]">
      <div className="bg-white px-5 pt-3 pb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button onClick={onBack} className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#111827" strokeWidth="2.5"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
          </button>
          <h1 className="text-[18px] font-black text-[#111827]" style={{ fontFamily: "Outfit, 'Noto Sans KR', sans-serif" }}>알림</h1>
        </div>
        <button className="w-9 h-9 rounded-xl bg-[#F4F6FB] flex items-center justify-center">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2"><polyline points="20 6 9 17 4 12"/><path d="M3 12l4 4"/></svg>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="px-5 pt-4 pb-2">
          <p className="text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">오늘</p>
        </div>
        <div className="bg-white">
          {todayItems.map((item, i) => (
            <div key={item.id}>
              <div className={`flex gap-3 px-5 py-4 ${item.unread ? "bg-[#F8FBFF]" : ""}`}>
                <NotifIcon type={item.icon} unread={item.unread} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-start justify-between gap-2 mb-0.5">
                    <p className="text-[14px] font-bold text-[#111827] leading-snug">{item.title}</p>
                    {item.unread && <div className="w-2 h-2 rounded-full bg-[#3B7BF8] flex-shrink-0 mt-1.5" />}
                  </div>
                  <p className="text-[13px] text-[#6B7280] leading-snug">{item.desc}</p>
                  <p className="text-[11px] text-[#CBD5E1] mt-1 font-medium">{item.time}</p>
                </div>
              </div>
              {i < todayItems.length - 1 && <div className="h-px bg-[#F4F6FB] mx-5" />}
            </div>
          ))}
        </div>

        <div className="px-5 pt-5 pb-2">
          <p className="text-[11px] font-black text-[#94A3B8] uppercase tracking-wider">어제</p>
        </div>
        <div className="bg-white">
          {yesterdayItems.map((item, i) => (
            <div key={item.id}>
              <div className="flex gap-3 px-5 py-4">
                <NotifIcon type={item.icon} unread={false} />
                <div className="flex-1 min-w-0">
                  <p className="text-[14px] font-semibold text-[#6B7280] leading-snug mb-0.5">{item.title}</p>
                  <p className="text-[13px] text-[#94A3B8] leading-snug">{item.desc}</p>
                  <p className="text-[11px] text-[#CBD5E1] mt-1 font-medium">{item.time}</p>
                </div>
              </div>
              {i < yesterdayItems.length - 1 && <div className="h-px bg-[#F4F6FB] mx-5" />}
            </div>
          ))}
        </div>
        <div className="h-8" />
      </div>
    </div>
  );
}
