import { useState } from "react";
import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../hooks/useAuth.js";
import { useIsMobile } from "../hooks/useIsMobile.js";
import Sidebar from "./Sidebar.jsx";

export default function ProtectedRoute() {
  const { user, loading } = useAuth();
  const isMobile = useIsMobile();
  const [navOpen, setNavOpen] = useState(false);

  if (loading) {
    return <div style={{ padding: 40, color: "#6B7280" }}>불러오는 중...</div>;
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div style={{ display: "flex" }}>
      <Sidebar open={navOpen} onClose={() => setNavOpen(false)} />

      {/* 좁은 화면에서는 사이드바가 서랍으로 빠지므로 왼쪽 여백을 주지 않는다 */}
      <div style={{
        marginLeft: isMobile ? 0 : 220,
        flex: 1,
        minWidth: 0,          // 자식이 넘쳐도 flex 칸이 늘어나지 않게 한다
        minHeight: "100vh",
        background: "#F9FAFB",
      }}>
        {isMobile && (
          <header style={S.topBar}>
            <button style={S.hamburger} onClick={() => setNavOpen(true)} aria-label="메뉴 열기">☰</button>
            <span style={S.topTitle}>또타24 현행화 시스템</span>
          </header>
        )}
        <Outlet />
      </div>
    </div>
  );
}

const S = {
  topBar:    { position: "sticky", top: 0, zIndex: 90, display: "flex", alignItems: "center", gap: 12,
               height: 52, padding: "0 14px", background: "#0D3B7A", color: "#fff" },
  hamburger: { background: "none", border: "none", color: "#fff", fontSize: 22, lineHeight: 1,
               cursor: "pointer", padding: "4px 6px", fontFamily: "inherit" },
  topTitle:  { fontSize: 14, fontWeight: 700 },
};
