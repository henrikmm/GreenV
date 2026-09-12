// O que as duas aplicações compartilham: as telas e as regras do domínio, nunca a origem dos
// dados. Um componente daqui recebe formatos; quem os busca é a aplicação que o usa.

export { LEVELS, EQUIPMENT_TYPES, getPolygonStyle, getLevel, formatArea, formatKm, getFeatureId,
         generateOrderId, ROAD_ANCHORS, kmToLatLng, vegetationLevel } from './utils/classification'
export { haversineKm, nearestKm, suggestCombinedRoutes, planRoute } from './utils/routePlanner'
export { buildOrder } from './utils/orders'
export { startOfWeek, formatWeekLabel, bucketByWeek } from './utils/date'
export { STATUS_MAP, PRIORITY_MAP } from './orderMeta'

export { AuthProvider, useAuth } from './context/AuthContext'
export { ToastProvider, useToast } from './context/ToastContext'

export { default as MapView } from './components/MapView'
export { default as Sidebar } from './components/Sidebar'
export { default as RoutePlannerPanel } from './components/RoutePlannerPanel'
export { default as OrderModal } from './components/OrderModal'
export { default as OrderHistoryModal } from './components/OrderHistoryModal'
export { default as NavBar, DEMO_TABS, LayoutDashboard, Map as MapIcon, ClipboardList, Users } from './components/NavBar'
export { default as UserMenu } from './components/UserMenu'
export { default as ProtectedRoute } from './components/ProtectedRoute'
export { default as Legend } from './components/Legend'
export { default as FitBounds } from './components/FitBounds'
export { default as BasemapTone } from './components/BasemapTone'
export { default as TimeScrubber } from './components/TimeScrubber'
export { default as ToastContainer } from './components/ToastContainer'

export { default as LevelDonut } from './components/charts/LevelDonut'
export { default as MiniCorridorMap } from './components/charts/MiniCorridorMap'
export { default as ProgressChart } from './components/charts/ProgressChart'
export { default as TeamBarChart } from './components/charts/TeamBarChart'
export { default as WeeklyBarChart } from './components/charts/WeeklyBarChart'

export { default as AnimatedNumber } from './components/ui/AnimatedNumber'
export { default as Badge } from './components/ui/Badge'
export { default as Card } from './components/ui/Card'
export { default as GrassHorizon } from './components/ui/GrassHorizon'
