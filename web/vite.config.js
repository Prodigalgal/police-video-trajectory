import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'

const {version}=JSON.parse(readFileSync(new URL('./package.json',import.meta.url),'utf8'))
const versionManifest={name:'application-version',generateBundle(){this.emitFile({type:'asset',fileName:'version.json',source:JSON.stringify({version})+'\n'})}}
export default defineConfig({ plugins: [react(),versionManifest], build: { assetsDir: '',rollupOptions:{input:{app:fileURLToPath(new URL('./index.html',import.meta.url)),map:fileURLToPath(new URL('./map-frame.html',import.meta.url))}} } })
