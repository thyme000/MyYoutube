import { ref, computed } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { upsertConversationRow, isGroupAnnouncementUnread, refreshConversationList } from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";

/** 群公告：加载 / 查看（关闭即标记已读）/ 编辑发布 */
export function useChatSessionAnnouncement(props, meta) {
  const annLoading = ref(false);
  const annText = ref("");
  const annVersion = ref(0);
  const annViewOpen = ref(false);
  const annEditOpen = ref(false);
  const annDraft = ref("");
  const annSaving = ref(false);

  async function loadAnnouncement() {
    if (!meta.isGroup.value) {
      annText.value = "";
      annVersion.value = 0;
      return;
    }
    const id = String(props.row?.conversationId || "");
    if (!id) {
      annText.value = "";
      annVersion.value = 0;
      return;
    }
    annLoading.value = true;
    try {
      const data = await chatApi.getAnnouncement(id);
      annText.value = data?.text != null ? String(data.text) : "";
      annVersion.value = Number(data?.version) || 0;
      if (annVersion.value > 0) {
        upsertConversationRow({
          conversationId: id,
          lastAnnouncementVersion: annVersion.value,
        });
      }
    } catch {
      annText.value = "";
      annVersion.value = 0;
    } finally {
      annLoading.value = false;
    }
  }

  const annRowSummary = computed(() => {
    if (annLoading.value) return "加载中…";
    const t = String(annText.value || "").trim();
    if (!t) return "暂无公告";
    return t.length > 28 ? `${t.slice(0, 28)}…` : t;
  });

  const showAnnUnread = computed(() => isGroupAnnouncementUnread(props.row));

  function openAnnView() {
    if (!meta.isGroup.value) return;
    annViewOpen.value = true;
  }

  async function closeAnnView() {
    const cid = String(props.row?.conversationId || "");
    const ver = annVersion.value;
    const hasBody = ver > 0 && String(annText.value || "").trim() !== "";
    annViewOpen.value = false;
    if (!cid || !meta.isGroup.value || !hasBody) return;
    try {
      await chatApi.markAnnouncementRead(cid);
      upsertConversationRow({
        conversationId: cid,
        lastSeenAnnouncementVersion: ver,
      });
    } catch {
      /* 忽略已读同步失败 */
    }
  }

  function openAnnEditFromView() {
    if (!meta.isGroupOwner.value) return;
    annDraft.value = String(annText.value || "");
    annEditOpen.value = true;
  }

  function closeAnnEdit() {
    annEditOpen.value = false;
  }

  async function confirmAnnPublish() {
    const cid = String(props.row?.conversationId || "");
    if (!cid || !meta.isGroupOwner.value) return;
    const raw = String(annDraft.value || "");
    const text = raw.trim();
    if (!text) {
      toast("公告内容不能为空", "info");
      return;
    }
    annSaving.value = true;
    try {
      const res = await chatApi.publishAnnouncement(cid, text);
      const v = Number(res?.version) || 0;
      annText.value = res?.text != null ? String(res.text) : text;
      annVersion.value = v || annVersion.value;
      annEditOpen.value = false;
      toast("已发布公告", "success");
      upsertConversationRow({
        conversationId: cid,
        lastAnnouncementVersion: v,
        lastSeenAnnouncementVersion: v,
      });
      await refreshConversationList(true);
    } catch (e) {
      toast(e?.message || "发布失败", "error");
    } finally {
      annSaving.value = false;
    }
  }

  return {
    annLoading,
    annText,
    annViewOpen,
    annEditOpen,
    annDraft,
    annSaving,
    annRowSummary,
    showAnnUnread,
    loadAnnouncement,
    openAnnView,
    closeAnnView,
    openAnnEditFromView,
    closeAnnEdit,
    confirmAnnPublish,
  };
}
