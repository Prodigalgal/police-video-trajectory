import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import Evaluation from './Evaluation.jsx'
import Admin from './Admin.jsx'
import Clues from './Clues.jsx'
import LiveMonitoring from './LiveMonitoring.jsx'
import CameraMapPage from './CameraMapPage.jsx'
import './styles.css'
import './workspace.css'
import {installChineseValidation} from './chinese-copy.js'

const removeValidation=installChineseValidation(document)
if(import.meta.hot)import.meta.hot.dispose(removeValidation)

createRoot(document.getElementById('root')).render(<React.StrictMode>{window.location.pathname.startsWith('/admin')?<Admin/>:window.location.pathname.startsWith('/clues')?<Clues/>:window.location.pathname.replace(/\/$/,'')==='/map'?<CameraMapPage/>:window.location.pathname.replace(/\/$/,'')==='/live'?<LiveMonitoring/>:window.location.pathname==='/evaluation'?<Evaluation/>:<App/>}</React.StrictMode>)
