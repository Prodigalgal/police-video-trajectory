"""Apply additive application migrations atomically before the application rolls forward."""
from pathlib import Path
import subprocess

root = Path('/app/db')
paths = [root/'schema.sql', root/'migrations/002-observation-uncertainty.sql', *sorted(root.glob('[0-9][0-9][0-9]-*.sql'))]
sql = 'BEGIN;\nSELECT pg_advisory_xact_lock(724019231);\n'
for path in paths:
    sql += path.read_text(encoding='utf-8')+'\n'
sql += 'COMMIT;\n'
subprocess.run(['psql','--no-psqlrc','--set=ON_ERROR_STOP=1','--quiet'],input=sql,text=True,check=True,timeout=240)
print('Application migrations completed.')
