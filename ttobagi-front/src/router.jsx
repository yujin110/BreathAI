import { createBrowserRouter, Navigate } from "react-router-dom";
import ProtectedRoute from "./components/ProtectedRoute.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import SignupPage from "./pages/SignupPage.jsx";
import ResetPage from "./pages/ResetPage.jsx";
import DashboardPage from "./pages/DashboardPage.jsx";
import AnalyzePage from "./pages/AnalyzePage.jsx";
import FaqPage from "./pages/FaqPage.jsx";
import FaqVersionsPage from "./pages/FaqVersionsPage.jsx";
import AuditLogPage from "./pages/AuditLogPage.jsx";
import AccountPage from "./pages/AccountPage.jsx";
import AdminPromotePage from "./pages/AdminPromotePage.jsx";

// basename은 vite.config.js의 base를 그대로 따라간다.
// GitHub Pages 프로젝트 사이트(/<레포>/)에서도 경로가 맞도록 한 곳에서만 관리한다.
const BASENAME = import.meta.env.BASE_URL.replace(/\/$/, "");

export const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  { path: "/signup", element: <SignupPage /> },
  { path: "/reset", element: <ResetPage /> },
  // 메일로 링크가 아니라 6자리 코드를 보내게 바뀌어서 별도 확정 화면이 없어졌다.
  // 예전 메일의 링크로 들어오는 경우를 위해 /reset으로 넘긴다.
  { path: "/reset/confirm", element: <Navigate to="/reset" replace /> },
  {
    element: <ProtectedRoute />,
    children: [
      { path: "/dashboard", element: <DashboardPage /> },
      { path: "/analyze", element: <AnalyzePage /> },
      { path: "/faq", element: <FaqPage /> },
      { path: "/faq/versions", element: <FaqVersionsPage /> },
      { path: "/audit-logs", element: <AuditLogPage /> },
      { path: "/account", element: <AccountPage /> },
      { path: "/promote", element: <AdminPromotePage /> },
    ],
  },
  { path: "/", element: <Navigate to="/dashboard" replace /> },
  { path: "*", element: <Navigate to="/dashboard" replace /> },
], { basename: BASENAME });
