// @ts-check
/**
 * 前后端契约的实体类型（JSDoc typedef，供 api 层各文件 import 引用）。
 * 后端统一响应 { code, message, data } 已在 http.js 拆包，这里描述的是 data 部分。
 * 雪花 id 一律以十进制字符串流转，避免 JSON 数字精度丢失。
 *
 * @typedef {string} SnowflakeId
 *
 * @typedef {Object} PageResult
 * @property {number} total
 * @property {number} pages
 * @property {Array<any>} list
 *
 * @typedef {Object} VideoCard 前端统一视频卡片（normalizeVideoListVo / normalizeVideo 产物）
 * @property {string} id
 * @property {string} title
 * @property {string} description
 * @property {any} tags
 * @property {string} author
 * @property {string} viewsText
 * @property {number=} playCount
 * @property {number=} likeCount
 * @property {number=} commentCount
 * @property {number=} shareCount
 * @property {string} videoUrl
 * @property {string} coverUrl
 * @property {string} avatarUrl 与 coverUrl 一致，兼容旧模板
 * @property {string} authorAvatarUrl
 * @property {SnowflakeId|any} authorId
 * @property {any} videoType
 * @property {any=} category
 * @property {any=} visibility
 * @property {any=} createTime
 * @property {number=} playbackTime
 * @property {string=} authorNickname
 * @property {boolean=} isFollow
 * @property {boolean=} isLike
 * @property {number=} allowComment 0/1
 *
 * @typedef {Object} VideoDetail 前端详情（normalizeVideoDetail 产物）
 * @property {string} id
 * @property {string} title
 * @property {string} description
 * @property {string} coverUrl
 * @property {string} videoUrl
 * @property {any} duration
 * @property {string} videoType
 * @property {any} playCount
 * @property {string} viewsText
 * @property {any} likeCount
 * @property {any} commentCount
 * @property {SnowflakeId|any} authorId
 * @property {string} authorNickname
 * @property {string} authorAvatarUrl
 * @property {any} authorFanCount
 * @property {number} playbackTime 毫秒
 * @property {any} visibility
 * @property {any} createTime
 * @property {any} isFollow
 * @property {boolean} isLike
 * @property {number} allowComment 0/1
 *
 * @typedef {Object} MergeJobStatus
 * @property {string} status DONE | FAIL | 进行中
 * @property {string=} videoObjectKey
 * @property {any=} durationSeconds
 * @property {string=} errorMessage
 *
 * @typedef {Object} UserVO 后端用户视图
 * @property {SnowflakeId=} userId
 * @property {string=} username
 * @property {string=} nickname
 * @property {string=} nickName
 * @property {string=} avatarUrl
 * @property {string=} bgImage
 * @property {string=} introduction
 * @property {any=} fans
 * @property {any=} followCount
 *
 * @typedef {Object} UserSearchRow 搜索「用户」Tab 行（normalizeUserSearchRow 产物）
 * @property {string} id
 * @property {string} nickname
 * @property {string} username
 * @property {string} avatarUrl
 * @property {string} introduction
 * @property {number} fans
 * @property {number} likeCount
 * @property {boolean} isFollow
 *
 * @typedef {Object} NotificationItem 通知行（normalizeNotificationItem 产物）
 * @property {any} id
 * @property {any} senderId
 * @property {string} senderNickname
 * @property {string} senderAvatarUrl
 * @property {string} type
 * @property {string} typeLabel
 * @property {string} content
 * @property {any} refId
 * @property {any} videoId
 * @property {string} createTime
 * @property {boolean} isRead
 * @property {string} conversationId
 * @property {string} conversationAvatarUrl
 * @property {string} conversationName
 * @property {string} extraJson
 *
 * @typedef {Object} CommentRow 评论行（normalizeCommentVo 产物）
 * @property {any} id
 * @property {string} content
 * @property {any} videoId
 * @property {any} userId
 * @property {string} nickname
 * @property {string} avatarUrl
 * @property {string} createTime
 * @property {any} status
 * @property {number} likeCount
 * @property {boolean} isLike
 * @property {number} replyCount
 * @property {any} parentId
 * @property {string} replyNickname
 * @property {any} replyUserId
 *
 * @typedef {Object} StoragePresign 前端直传预签名结果
 * @property {string} url 预签名 PUT URL
 * @property {string} objectKey
 */
export {};
