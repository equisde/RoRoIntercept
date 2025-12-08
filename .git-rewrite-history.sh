#!/bin/bash
# Script to remove keystore from git history

echo "🔄 Removing keystore from git history..."

# Method 1: Using git filter-branch (built-in)
git filter-branch --force --index-filter \
  'git rm --cached --ignore-unmatch app/roro-release.keystore' \
  --prune-empty --tag-name-filter cat -- --all

echo "✅ Keystore removed from history"
echo "⚠️  Run: git push --force --all origin"
echo "⚠️  This will rewrite history on remote!"
