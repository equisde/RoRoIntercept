#!/bin/bash

echo "🚀 Creating GitHub Repository: RoRoIntercept"
echo "============================================="
echo ""

# Check if gh is authenticated
if ! gh auth status &>/dev/null; then
    echo "⚠️  GitHub CLI not authenticated"
    echo "Please run: gh auth login"
    exit 1
fi

echo "✓ GitHub CLI authenticated"
echo ""

# Create repository
echo "📦 Creating repository..."
gh repo create RoRoIntercept \
    --public \
    --description "🔍 Intercepta y modifica tráfico HTTP/HTTPS en Android - Como Fiddler Everywhere" \
    --homepage "https://github.com/YOUR_USERNAME/RoRoIntercept" \
    --source=. \
    --remote=origin \
    --push

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Repository created successfully!"
    echo ""
    echo "🌐 Repository URL:"
    gh repo view --web
    echo ""
    echo "📋 Next steps:"
    echo "  1. Go to: Settings → Actions → General"
    echo "  2. Enable 'Read and write permissions' for GITHUB_TOKEN"
    echo "  3. GitHub Actions will automatically build APKs on push"
    echo "  4. Download APKs from: Actions → Build → Artifacts"
    echo ""
else
    echo ""
    echo "❌ Failed to create repository"
    echo "You can create it manually:"
    echo "  gh repo create RoRoIntercept --public --source=. --remote=origin --push"
fi
