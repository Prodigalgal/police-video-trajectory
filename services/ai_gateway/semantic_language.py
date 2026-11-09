"""User-facing semantics are Chinese; protocol identifiers and OCR evidence stay exact."""
import re
from typing import Annotated
from pydantic import AfterValidator
from pydantic_core import PydanticCustomError

HAN=re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff]')
LATIN=re.compile(r'[A-Za-z]')

def chinese_semantic_text(value:str)->str:
    chinese=len(HAN.findall(value))
    # Keep short names/acronyms inside Chinese descriptions, never an English
    # paragraph with a token Chinese prefix. Unknown facts must remain null.
    if not chinese or len(LATIN.findall(value))>max(12,chinese*2):
        raise PydanticCustomError('chinese_semantics','模型识别的描述、动作和属性值必须使用中文')
    return value

ChineseSemanticText=Annotated[str,AfterValidator(chinese_semantic_text)]
