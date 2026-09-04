import { useState } from "react";
import React from "react";
import LoginScreen from "./screens/LoginScreen";
import MyTripsScreen from "./screens/MyTripsScreen";
import TripDetailScreen from "./screens/TripDetailScreen";
import CreateTripScreen from "./screens/CreateTripScreen";
import ScheduleEditScreen from "./screens/ScheduleEditScreen";
import ActiveTravelScreen from "./screens/ActiveTravelScreen";
import AddPlaceScreen from "./screens/AddPlaceScreen";
import PlaceDetailScreen from "./screens/PlaceDetailScreen";
import EditTripScreen from "./screens/EditTripScreen";
import RoutePreviewScreen from "./screens/RoutePreviewScreen";
import AlternativePlacesScreen from "./screens/AlternativePlacesScreen";
import SettingsScreen from "./screens/SettingsScreen";
import LocationPermissionScreen from "./screens/LocationPermissionScreen";
import ErrorScreen from "./screens/ErrorScreen";
import VariableMonitorScreen from "./screens/VariableMonitorScreen";
import RouteRecalculatingScreen from "./screens/RouteRecalculatingScreen";
import NotificationsScreen from "./screens/NotificationsScreen";
import MapSearchScreen from "./screens/MapSearchScreen";
import DayRouteScreen from "./screens/DayRouteScreen";

type Screen =
  | "login" | "myTrips" | "tripDetail" | "createTrip" | "scheduleEdit"
  | "activeTravel" | "addPlace" | "placeDetail" | "editTrip" | "routePreview"
  | "alternativePlaces" | "alternativesEmpty" | "settings" | "locationPermission" | "error"
  | "variableMonitor" | "variableMonitorClear" | "routeRecalculating" | "notifications" | "mapSearch" | "dayRoute";

const BOTTOM_NAV_SCREENS: Screen[] = ["myTrips", "activeTravel", "settings"];
type NavTab = "myTrips" | "activeTravel" | "settings";

const NAV_ITEMS: { id: NavTab; label: string; icon: (active: boolean) => React.ReactElement }[] = [
  {
    id: "myTrips", label: "내 여행",
    icon: (a) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={a ? "#3B7BF8" : "#94A3B8"} strokeWidth={a ? "2" : "1.8"}>
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>
      </svg>
    ),
  },
  {
    id: "activeTravel", label: "여행 중",
    icon: (a) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={a ? "#3B7BF8" : "#94A3B8"} strokeWidth={a ? "2" : "1.8"}>
        <polygon points="3 11 22 2 13 21 11 13 3 11"/>
      </svg>
    ),
  },
  {
    id: "settings", label: "설정",
    icon: (a) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={a ? "#3B7BF8" : "#94A3B8"} strokeWidth={a ? "2" : "1.8"}>
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
      </svg>
    ),
  },
];

const ALL_SCREENS: { id: Screen; label: string }[] = [
  { id: "login", label: "로그인" }, { id: "myTrips", label: "내 여행" },
  { id: "tripDetail", label: "여행 상세" }, { id: "createTrip", label: "새 여행" },
  { id: "scheduleEdit", label: "일정 편집" }, { id: "activeTravel", label: "여행 중" },
  { id: "addPlace", label: "장소 추가" }, { id: "placeDetail", label: "장소 상세" },
  { id: "editTrip", label: "여행 수정" }, { id: "routePreview", label: "경로 변경" },
  { id: "alternativePlaces", label: "대체 장소" }, { id: "settings", label: "설정" },
  { id: "locationPermission", label: "위치 권한" }, { id: "error", label: "오류 안내" },
  { id: "variableMonitor", label: "변수 감지" }, { id: "variableMonitorClear", label: "변수 없음" },
  { id: "routeRecalculating", label: "경로 재생성" },
  { id: "alternativesEmpty", label: "대체 없음" },
  { id: "notifications", label: "알림" }, { id: "mapSearch", label: "지도 검색" },
  { id: "dayRoute", label: "일자 경로" },
];

export default function App() {
  const [screen, setScreen] = useState<Screen>("login");
  const [history, setHistory] = useState<Screen[]>([]);
  const [fromNewTrip, setFromNewTrip] = useState(false);

  const navigate = (next: Screen) => { setHistory((h) => [...h, screen]); setScreen(next); };
  const goBack = () => {
    const prev = history[history.length - 1];
    if (prev) { setHistory((h) => h.slice(0, -1)); setScreen(prev); }
  };
  const switchTab = (tab: NavTab) => { setHistory([]); setScreen(tab); };

  const showBottomNav = BOTTOM_NAV_SCREENS.includes(screen);
  const activeTab = (BOTTOM_NAV_SCREENS.includes(screen) ? screen : null) as NavTab | null;

  return (
    <div className="min-h-full flex items-center justify-center p-4" style={{ background: "linear-gradient(135deg, #0B1120 0%, #1A2540 100%)" }}>
      <div
        className="relative w-full max-w-[390px] bg-[#F4F6FB] overflow-hidden flex flex-col"
        style={{
          height: "min(844px, calc(100vh - 32px))",
          borderRadius: "44px",
          boxShadow: "0 40px 100px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.06), inset 0 1px 0 rgba(255,255,255,0.1)",
        }}
      >
        {/* Status bar */}
        {screen !== "login" && (
          <div className="flex-shrink-0 h-11 bg-white flex items-center px-6 justify-between z-20">
            <span className="text-[13px] font-bold text-[#111827]" style={{ fontFamily: "Outfit, sans-serif" }}>9:41</span>
            <div className="flex items-center gap-2">
              <svg width="16" height="11" viewBox="0 0 16 11" fill="#111827">
                <rect x="0" y="3.5" width="2.5" height="7.5" rx="1" opacity="0.4"/>
                <rect x="4" y="2" width="2.5" height="9" rx="1" opacity="0.6"/>
                <rect x="8" y="0.5" width="2.5" height="10.5" rx="1" opacity="0.8"/>
                <rect x="12" y="0" width="2.5" height="11" rx="1"/>
              </svg>
              <svg width="15" height="11" viewBox="0 0 15 11" fill="none">
                <path d="M7.5 2C5.2 2 3.1 3 1.6 4.7L0.5 3.6C2.3 1.4 5 0 7.5 0s5.2 1.4 7 3.6L13.4 4.7C11.9 3 9.8 2 7.5 2z" fill="#111827" opacity="0.5"/>
                <path d="M7.5 5c-1.6 0-3 .7-4 1.8L2.3 5.6C3.7 4 5.5 3 7.5 3s3.8 1 5.2 2.6L11.5 6.8C10.5 5.7 9.1 5 7.5 5z" fill="#111827" opacity="0.75"/>
                <circle cx="7.5" cy="9.5" r="1.5" fill="#111827"/>
              </svg>
              <div className="flex items-center gap-0.5">
                <div className="w-5 h-2.5 rounded-sm border border-[#111827]/40 p-px flex items-center">
                  <div className="h-full bg-[#111827] rounded-sm" style={{ width: "80%" }}/>
                </div>
                <div className="w-0.5 h-1.5 bg-[#111827]/40 rounded-r-sm" />
              </div>
            </div>
          </div>
        )}

        {/* Screen */}
        <div className="flex-1 relative overflow-hidden">
          {screen === "login" && <LoginScreen onLogin={() => navigate("locationPermission")} />}
          {screen === "locationPermission" && <LocationPermissionScreen onBack={goBack} onAllow={() => navigate("myTrips")} onManual={() => navigate("myTrips")} />}
          {screen === "myTrips" && <MyTripsScreen onSelectTrip={() => navigate("tripDetail")} onCreateTrip={() => navigate("createTrip")} onNotifications={() => navigate("notifications")} />}
          {screen === "tripDetail" && <TripDetailScreen onBack={goBack} onStartTravel={() => navigate("activeTravel")} onGoToActive={() => switchTab("activeTravel")} onSelectPlace={() => navigate("placeDetail")} onEditSchedule={() => { setFromNewTrip(false); navigate("scheduleEdit"); }} onEditTrip={() => navigate("editTrip")} />}
          {screen === "createTrip" && <CreateTripScreen onBack={goBack} onCreate={() => { setFromNewTrip(true); navigate("scheduleEdit"); }} />}
          {screen === "scheduleEdit" && <ScheduleEditScreen onBack={goBack} onSave={() => navigate("tripDetail")} onAddPlace={() => navigate("addPlace")} isNew={fromNewTrip} />}
          {screen === "activeTravel" && <ActiveTravelScreen onSettings={() => navigate("editTrip")} onAlternative={() => navigate("alternativePlaces")} onAddPlace={() => navigate("addPlace")} onRoutePreview={() => navigate("routePreview")} onDayRoute={() => navigate("dayRoute")} onVariableMonitor={() => navigate("variableMonitor")} onNotifications={() => navigate("notifications")} />}
          {screen === "addPlace" && <AddPlaceScreen onBack={goBack} onSelectPlace={() => goBack()} onViewPlace={() => navigate("placeDetail")} />}
          {screen === "placeDetail" && <PlaceDetailScreen onBack={goBack} onAddToSchedule={goBack} />}
          {screen === "dayRoute" && <DayRouteScreen onBack={goBack} />}
          {screen === "editTrip" && <EditTripScreen onBack={goBack} onSave={goBack} />}
          {screen === "routePreview" && <RoutePreviewScreen onBack={goBack} onApprove={() => { navigate("routeRecalculating"); }} onOtherCandidates={() => navigate("alternativePlaces")} />}
          {screen === "alternativePlaces" && <AlternativePlacesScreen onBack={goBack} onKeepSchedule={() => switchTab("activeTravel")} onSearchManually={() => navigate("mapSearch")} onRouteCompare={() => navigate("routePreview")} />}
          {screen === "settings" && <SettingsScreen onLogout={() => { setHistory([]); setScreen("login"); }} />}
          {screen === "error" && <ErrorScreen onBack={goBack} onRetry={() => navigate("activeTravel")} />}
          {screen === "variableMonitor" && <VariableMonitorScreen onBack={goBack} onAlternative={() => navigate("alternativePlaces")} />}
          {screen === "variableMonitorClear" && <VariableMonitorScreen onBack={goBack} onAlternative={() => navigate("alternativePlaces")} hasAlerts={false} />}
          {screen === "alternativesEmpty" && <AlternativePlacesScreen onBack={goBack} onKeepSchedule={() => switchTab("activeTravel")} onSearchManually={() => navigate("mapSearch")} onRouteCompare={() => navigate("routePreview")} hasResults={false} />}
          {screen === "routeRecalculating" && <RouteRecalculatingScreen onBack={goBack} />}
          {screen === "notifications" && <NotificationsScreen onBack={goBack} />}
          {screen === "mapSearch" && <MapSearchScreen onBack={goBack} onSelectPlace={() => navigate("routePreview")} />}
        </div>

        {/* Bottom nav */}
        {showBottomNav && (
          <div className="flex-shrink-0 bg-white flex items-center justify-around px-4 pt-3 pb-6" style={{ boxShadow: "0 -1px 0 #E2E8F0" }}>
            {NAV_ITEMS.map((item) => (
              <button key={item.id} onClick={() => switchTab(item.id)} className="flex flex-col items-center gap-1 flex-1 relative">
                {item.icon(activeTab === item.id)}
                <span className={`text-[11px] font-semibold ${activeTab === item.id ? "text-[#3B7BF8]" : "text-[#94A3B8]"}`}>{item.label}</span>
                {activeTab === item.id && <div className="absolute -bottom-3 left-1/2 -translate-x-1/2 w-1 h-1 rounded-full bg-[#3B7BF8]" />}
              </button>
            ))}
          </div>
        )}

        {/* Demo switcher */}
        <div className="flex-shrink-0 bg-[#0B1120] px-3 py-2 overflow-x-auto">
          <div className="flex gap-1.5 min-w-max">
            {ALL_SCREENS.map((s) => (
              <button
                key={s.id}
                onClick={() => { setHistory([]); setScreen(s.id); }}
                className={`px-2.5 py-1.5 rounded-lg text-[10px] font-semibold whitespace-nowrap transition-colors ${
                  screen === s.id ? "bg-[#3B7BF8] text-white" : "bg-white/10 text-white/50"
                }`}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
