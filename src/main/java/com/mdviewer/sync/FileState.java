package com.mdviewer.sync;

/**
 * One file as this machine currently holds it.
 *
 * <p>Identified by content rather than by time. Modification timestamps disagree between
 * machines, survive copies that changed nothing, and are wrong after a restore from
 * backup - so a sync that decides from them moves files that did not change and, worse,
 * misses files that did.
 *
 * @param path workspace-relative, always with forward slashes so the same document has
 *             the same name whichever platform it was last edited on
 * @param hash lowercase hex SHA-256 of the content
 * @param size bytes, so a plan can say what a sync will cost before it starts
 */
public record FileState(String path, String hash, long size) {
}
