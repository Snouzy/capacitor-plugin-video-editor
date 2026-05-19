import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core/types/definitions';
import type { EditOptions, MediaFileResult, ProgressInfo, ThumbnailOptions, VideoEditorPlugin } from './definitions';
export declare class VideoEditorWeb extends WebPlugin implements VideoEditorPlugin {
    edit(options: EditOptions): Promise<MediaFileResult>;
    thumbnail(options: ThumbnailOptions): Promise<MediaFileResult>;
    addListener(eventName: 'transcodeProgress', _listenerFunc: (info: ProgressInfo) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
}
