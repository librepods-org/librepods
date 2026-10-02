param(
    [string]$ResourceRoot = (Join-Path $PSScriptRoot '../app/src/main/res')
)

$ErrorActionPreference = 'Stop'

function Read-TextResources([string]$Directory) {
    $result = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*.xml') {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        foreach ($node in $document.SelectNodes('/resources/string | /resources/plurals')) {
            $key = $node.LocalName + '/' + $node.GetAttribute('name')
            if ($result.ContainsKey($key)) { throw "Duplicate resource: $key in $Directory" }
            $result[$key] = $node
        }
    }
    return $result
}

function Format-Arguments([string]$Value) {
    # Ignore escaped percent signs; retain argument indexes and conversion types.
    $valueWithoutPercentLiterals = $Value.Replace('%%', '')
    return ([regex]::Matches($valueWithoutPercentLiterals,
        '%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?(?:[tT])?[a-zA-Z]') |
        ForEach-Object { $_.Value } | Sort-Object) -join '|'
}

$english = Read-TextResources (Join-Path $ResourceRoot 'values')
$korean = Read-TextResources (Join-Path $ResourceRoot 'values-ko')
$checked = 0
foreach ($key in $english.Keys) {
    $source = $english[$key]
    if ($source.GetAttribute('translatable') -eq 'false') { continue }
    if (-not $korean.ContainsKey($key)) { throw "Missing Korean translation: $key" }
    $translation = $korean[$key]
    if ($source.LocalName -eq 'plurals') {
        if (-not $translation.SelectSingleNode('item[@quantity="other"]')) {
            throw "Missing Korean plural fallback: $key"
        }
        $pairs = foreach ($item in $translation.SelectNodes('item')) {
            $quantity = $item.GetAttribute('quantity')
            $original = $source.SelectSingleNode("item[@quantity='$quantity']")
            if (-not $original) { $original = $source.SelectSingleNode('item[@quantity="other"]') }
            @{ Source = $original; Translation = $item }
        }
    } else {
        $pairs = @(@{ Source = $source; Translation = $translation })
    }
    foreach ($pair in $pairs) {
        if ([string]::IsNullOrWhiteSpace($pair.Translation.InnerText)) {
            throw "Empty Korean translation: $key"
        }
        if ((Format-Arguments $pair.Source.InnerText) -ne (Format-Arguments $pair.Translation.InnerText)) {
            throw "Format argument mismatch: $key"
        }
    }
    $checked++
}
foreach ($key in $korean.Keys) {
    if (-not $english.ContainsKey($key)) { throw "Korean resource without an English original: $key" }
}
Write-Output "PASS: $checked Korean resources; no missing/duplicate/empty entries or format argument mismatches."
