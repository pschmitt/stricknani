"""Pydantic request/response schemas for the JSON API (`/api/v1`)."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class MetaResponse(BaseModel):
    """Server identity, for the app to detect it needs a full resync."""

    version: str
    build_id: str


class CategoryResponse(BaseModel):
    id: int
    name: str


class CategoryCreateRequest(BaseModel):
    name: str


class YarnPhotoResponse(BaseModel):
    id: int
    url: str
    thumbnail_url: str
    alt_text: str
    is_primary: bool


class YarnResponse(BaseModel):
    id: int
    name: str
    description: str | None = None
    brand: str | None = None
    colorway: str | None = None
    dye_lot: str | None = None
    fiber_content: str | None = None
    weight_category: str | None = None
    recommended_needles: str | None = None
    weight_grams: int | None = None
    length_meters: int | None = None
    notes: str | None = None
    link: str | None = None
    link_archive: str | None = None
    is_ai_enhanced: bool
    is_favorite: bool
    created_at: datetime
    updated_at: datetime
    project_ids: list[int] = Field(default_factory=list)
    photos: list[YarnPhotoResponse] = Field(default_factory=list)


class YarnListItemResponse(BaseModel):
    id: int
    name: str
    brand: str | None = None
    colorway: str | None = None
    weight_category: str | None = None
    is_favorite: bool
    updated_at: datetime
    preview_url: str | None = None


class YarnWriteRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str
    description: str | None = None
    brand: str | None = None
    colorway: str | None = None
    dye_lot: str | None = None
    fiber_content: str | None = None
    weight_category: str | None = None
    recommended_needles: str | None = None
    weight_grams: int | None = None
    length_meters: int | None = None
    notes: str | None = None
    link: str | None = None
    is_ai_enhanced: bool = False


class StepResponse(BaseModel):
    id: int
    title: str
    description: str | None = None
    step_number: int


class StepWriteRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    title: str
    description: str | None = None
    step_number: int = 0


class ImageResponse(BaseModel):
    id: int
    url: str
    thumbnail_url: str
    alt_text: str
    image_type: str
    is_title_image: bool
    is_stitch_sample: bool
    step_id: int | None = None


class AttachmentResponse(BaseModel):
    id: int
    filename: str
    original_filename: str
    content_type: str
    size_bytes: int
    url: str


class ProjectResponse(BaseModel):
    id: int
    name: str
    category: str | None = None
    yarn: str | None = None
    needles: str | None = None
    stitch_sample: str | None = None
    description: str | None = None
    notes: str | None = None
    other_materials: str | None = None
    tags: list[str] = Field(default_factory=list)
    link: str | None = None
    link_archive: str | None = None
    is_ai_enhanced: bool
    is_favorite: bool
    created_at: datetime
    updated_at: datetime
    yarn_ids: list[int] = Field(default_factory=list)
    steps: list[StepResponse] = Field(default_factory=list)
    images: list[ImageResponse] = Field(default_factory=list)
    attachments: list[AttachmentResponse] = Field(default_factory=list)


class ProjectListItemResponse(BaseModel):
    id: int
    name: str
    category: str | None = None
    tags: list[str] = Field(default_factory=list)
    is_favorite: bool
    updated_at: datetime
    preview_url: str | None = None


class ProjectWriteRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str
    category: str | None = None
    needles: str | None = None
    stitch_sample: str | None = None
    description: str | None = None
    notes: str | None = None
    other_materials: str | None = None
    tags: list[str] = Field(default_factory=list)
    link: str | None = None
    is_ai_enhanced: bool = False
    yarn_ids: list[int] = Field(default_factory=list)
    steps: list[StepWriteRequest] = Field(default_factory=list)


class YarnPage(BaseModel):
    """A single page of the yarn list endpoint."""

    items: list[YarnListItemResponse]
    page: int
    per_page: int
    has_more: bool


class ProjectPage(BaseModel):
    """A single page of the project list endpoint."""

    items: list[ProjectListItemResponse]
    page: int
    per_page: int
    has_more: bool
